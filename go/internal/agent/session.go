package agent

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/google/uuid"
	"golang.org/x/net/websocket"

	"htunnel/go/internal/protocol"
	"htunnel/go/internal/shared"
)

type openResult struct {
	ok     bool
	reason string
}

type Session struct {
	cfg Config

	mu      sync.RWMutex
	ws      *websocket.Conn
	closed  bool
	writeMu sync.Mutex

	streams      *shared.SafeMap[string, *wsStream]
	pendingOpens *shared.SafeMap[string, chan openResult]
}

func NewSession(cfg Config) *Session {
	return &Session{
		cfg:          cfg,
		streams:      shared.NewSafeMap[string, *wsStream](),
		pendingOpens: shared.NewSafeMap[string, chan openResult](),
	}
}

func (s *Session) Run(ctx context.Context) {
	backoff := time.Second
	for {
		select {
		case <-ctx.Done():
			s.shutdownAll()
			return
		default:
		}

		if err := s.connectAndServe(ctx); err != nil {
			log.Printf("session disconnected: %v", err)
		}

		s.detach()
		s.failAllPending("disconnected")
		s.closeAllStreams(false)

		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		if backoff < 10*time.Second {
			backoff *= 2
		}
	}
}

func (s *Session) connectAndServe(ctx context.Context) error {
	origin := "http://localhost/"
	cfg, err := websocket.NewConfig(s.cfg.Server.URL, origin)
	if err != nil {
		return fmt.Errorf("new websocket config: %w", err)
	}
	cfg.Version = websocket.ProtocolVersionHybi13
	cfg.Dialer = &net.Dialer{Timeout: time.Duration(s.cfg.Server.ConnectTimeoutSec) * time.Second}
	cfg.Header = make(http.Header)

	conn, err := websocket.DialConfig(cfg)
	if err != nil {
		return fmt.Errorf("dial websocket: %w", err)
	}

	if err := s.attach(conn); err != nil {
		_ = conn.Close()
		return err
	}
	log.Printf("connected to %s", s.cfg.Server.URL)

	if err := s.send(&protocol.Envelope{
		Type:         protocol.TypeAuth,
		Token:        s.cfg.Auth.Token,
		AgentID:      s.cfg.Agent.ID,
		AgentVersion: s.cfg.Agent.Version,
		Ts:           time.Now().UnixMilli(),
	}); err != nil {
		_ = conn.Close()
		return fmt.Errorf("send auth: %w", err)
	}

	_ = conn.SetReadDeadline(time.Now().Add(15 * time.Second))
	var data []byte
	if err := websocket.Message.Receive(conn, &data); err != nil {
		_ = conn.Close()
		return fmt.Errorf("read auth response: %w", err)
	}
	env, err := protocol.Unmarshal(data)
	if err != nil {
		_ = conn.Close()
		return fmt.Errorf("parse auth response: %w", err)
	}
	if env.Type != protocol.TypeAuthResp || !env.Ok {
		_ = conn.Close()
		if env.Reason == "" {
			env.Reason = "auth rejected"
		}
		return errors.New(env.Reason)
	}

	_ = conn.SetReadDeadline(time.Time{})

	errCh := make(chan error, 1)
	go s.pingLoop(ctx, errCh)
	go s.readLoop(errCh)

	select {
	case <-ctx.Done():
		return nil
	case err := <-errCh:
		return err
	}
}

func (s *Session) readLoop(errCh chan<- error) {
	for {
		conn := s.currentConn()
		if conn == nil {
			errCh <- errors.New("connection detached")
			return
		}
		_ = conn.SetReadDeadline(time.Now().Add(time.Duration(s.cfg.Runtime.PingIntervalSec*3) * time.Second))
		var data []byte
		if err := websocket.Message.Receive(conn, &data); err != nil {
			errCh <- err
			return
		}
		env, err := protocol.Unmarshal(data)
		if err != nil {
			log.Printf("ignore invalid protobuf payload: %v", err)
			continue
		}
		s.handleInbound(env)
	}
}

func (s *Session) pingLoop(ctx context.Context, errCh chan<- error) {
	ticker := time.NewTicker(time.Duration(s.cfg.Runtime.PingIntervalSec) * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := s.send(&protocol.Envelope{Type: protocol.TypePing, Ts: time.Now().UnixMilli()}); err != nil {
				errCh <- err
				return
			}
		}
	}
}

func (s *Session) handleInbound(env *protocol.Envelope) {
	switch env.Type {
	case protocol.TypeOpenResp:
		ch, ok := s.pendingOpens.Get(env.ConnID)
		if ok {
			select {
			case ch <- openResult{ok: env.Ok, reason: env.Reason}:
			default:
			}
		}
	case protocol.TypeData:
		stream, ok := s.streams.Get(env.ConnID)
		if ok {
			if err := stream.push(env.Payload); err != nil {
				_ = stream.Close()
			}
		}
	case protocol.TypeClose:
		stream, ok := s.streams.Get(env.ConnID)
		if ok {
			stream.closeRemote()
			s.streams.Delete(env.ConnID)
		}
	case protocol.TypePing:
		_ = s.send(&protocol.Envelope{Type: protocol.TypePong, Ts: env.Ts})
	case protocol.TypePong:
		// heartbeat ack
	case protocol.TypeError:
		log.Printf("server error conn_id=%s reason=%s", env.ConnID, env.Reason)
	default:
		log.Printf("ignore unknown message type: %s", env.Type)
	}
}

func (s *Session) OpenStream(ctx context.Context, dstVIP string, dstPort uint32, srcAddr string) (netConn *wsStream, err error) {
	connID := uuid.NewString()
	stream := newWSStream(connID, s)
	s.streams.Set(connID, stream)

	resultCh := make(chan openResult, 1)
	s.pendingOpens.Set(connID, resultCh)

	err = s.send(&protocol.Envelope{
		Type:    protocol.TypeOpen,
		ConnID:  connID,
		DstVIP:  dstVIP,
		DstPort: dstPort,
		SrcAddr: srcAddr,
	})
	if err != nil {
		s.pendingOpens.Delete(connID)
		s.streams.Delete(connID)
		return nil, err
	}

	select {
	case <-ctx.Done():
		s.pendingOpens.Delete(connID)
		s.streams.Delete(connID)
		_ = s.send(&protocol.Envelope{Type: protocol.TypeClose, ConnID: connID, Reason: "open timeout"})
		return nil, ctx.Err()
	case result := <-resultCh:
		s.pendingOpens.Delete(connID)
		if !result.ok {
			s.streams.Delete(connID)
			_ = s.send(&protocol.Envelope{Type: protocol.TypeClose, ConnID: connID, Reason: result.reason})
			if result.reason == "" {
				result.reason = "open rejected"
			}
			return nil, errors.New(result.reason)
		}
		return stream, nil
	}
}

func (s *Session) onLocalClose(connID string) {
	s.streams.Delete(connID)
	_ = s.send(&protocol.Envelope{Type: protocol.TypeClose, ConnID: connID, Reason: "local close"})
}

func (s *Session) send(env *protocol.Envelope) error {
	data, err := protocol.Marshal(env)
	if err != nil {
		return err
	}
	conn := s.currentConn()
	if conn == nil {
		return errors.New("websocket not connected")
	}
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	_ = conn.SetWriteDeadline(time.Now().Add(time.Duration(s.cfg.Runtime.WriteTimeoutSec) * time.Second))
	return websocket.Message.Send(conn, data)
}

func (s *Session) attach(conn *websocket.Conn) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return errors.New("session closed")
	}
	s.ws = conn
	return nil
}

func (s *Session) detach() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.ws != nil {
		_ = s.ws.Close()
	}
	s.ws = nil
}

func (s *Session) currentConn() *websocket.Conn {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.ws
}

func (s *Session) failAllPending(reason string) {
	s.pendingOpens.Range(func(connID string, ch chan openResult) {
		select {
		case ch <- openResult{ok: false, reason: reason}:
		default:
		}
		s.pendingOpens.Delete(connID)
	})
}

func (s *Session) closeAllStreams(notifyRemote bool) {
	s.streams.Range(func(connID string, st *wsStream) {
		if notifyRemote {
			_ = s.send(&protocol.Envelope{Type: protocol.TypeClose, ConnID: connID, Reason: "shutdown"})
		}
		st.closeRemote()
		s.streams.Delete(connID)
	})
}

func (s *Session) shutdownAll() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closed = true
	if s.ws != nil {
		_ = s.ws.Close()
		s.ws = nil
	}
	s.failAllPending("agent shutdown")
	s.closeAllStreams(false)
}
