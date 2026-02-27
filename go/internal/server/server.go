package server

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"strconv"
	"sync"
	"time"

	"golang.org/x/net/websocket"

	"htunnel/go/internal/protocol"
	"htunnel/go/internal/shared"
)

type Service struct {
	cfg Config
}

func New(cfg Config) *Service {
	cfg.normalize()
	return &Service{cfg: cfg}
}

func (s *Service) Run(ctx context.Context) error {
	mux := http.NewServeMux()
	mux.HandleFunc(s.cfg.Listen.LoginPath, s.handleAgentLogin)
	mux.Handle(s.cfg.Listen.Path, websocket.Handler(s.handleWS))

	httpServer := &http.Server{
		Addr:    s.cfg.Listen.Addr,
		Handler: mux,
	}

	errCh := make(chan error, 1)
	go func() {
		var err error
		if s.cfg.Listen.TLS.Enabled {
			log.Printf("server listen %s tls=true path=%s", s.cfg.Listen.Addr, s.cfg.Listen.Path)
			err = httpServer.ListenAndServeTLS(s.cfg.Listen.TLS.CertFile, s.cfg.Listen.TLS.KeyFile)
		} else {
			log.Printf("server listen %s tls=false path=%s", s.cfg.Listen.Addr, s.cfg.Listen.Path)
			err = httpServer.ListenAndServe()
		}
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
		close(errCh)
	}()

	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		return httpServer.Shutdown(shutdownCtx)
	case err := <-errCh:
		return err
	}
}

type wsSession struct {
	cfg  Config
	conn *websocket.Conn

	writeMu sync.Mutex

	authed bool
	agent  string

	conns *shared.SafeMap[string, net.Conn]
}

func (s *Service) handleWS(ws *websocket.Conn) {
	defer ws.Close()

	session := &wsSession{
		cfg:   s.cfg,
		conn:  ws,
		conns: shared.NewSafeMap[string, net.Conn](),
	}
	defer session.closeAll()

	_ = ws.SetDeadline(time.Now().Add(s.cfg.idleTimeout()))

	pingDone := make(chan struct{})
	go session.pingLoop(pingDone)
	defer close(pingDone)

	for {
		var data []byte
		if err := websocket.Message.Receive(ws, &data); err != nil {
			log.Printf("read message error: %v", err)
			return
		}
		_ = ws.SetDeadline(time.Now().Add(s.cfg.idleTimeout()))

		env, err := protocol.Unmarshal(data)
		if err != nil {
			_ = session.send(&protocol.Envelope{Type: protocol.TypeError, Reason: "invalid protobuf payload"})
			continue
		}

		if !session.authed {
			if env.Type != protocol.TypeAuth {
				_ = session.send(&protocol.Envelope{Type: protocol.TypeError, Reason: "unauthorized"})
				return
			}
			if err := session.authenticate(env); err != nil {
				_ = session.send(&protocol.Envelope{Type: protocol.TypeAuthResp, Ok: false, Reason: err.Error()})
				return
			}
			_ = session.send(&protocol.Envelope{Type: protocol.TypeAuthResp, Ok: true, Ts: time.Now().UnixMilli()})
			continue
		}

		session.handle(env)
	}
}

func (s *wsSession) pingLoop(done <-chan struct{}) {
	ticker := time.NewTicker(s.cfg.pingInterval())
	defer ticker.Stop()
	for {
		select {
		case <-done:
			return
		case <-ticker.C:
			if err := s.send(&protocol.Envelope{Type: protocol.TypePing, Ts: time.Now().UnixMilli()}); err != nil {
				return
			}
		}
	}
}

func (s *wsSession) authenticate(env *protocol.Envelope) error {
	if env.Token == "" {
		return fmt.Errorf("empty token")
	}
	if err := validateHS256Token(env.Token, s.cfg.Auth.JWT.Secret, s.cfg.Auth.JWT.Issuer, s.cfg.Auth.JWT.Audience, s.cfg.clockSkew()); err != nil {
		return err
	}
	s.authed = true
	s.agent = env.AgentID
	if s.agent == "" {
		s.agent = "unknown-agent"
	}
	log.Printf("agent authenticated: %s", s.agent)
	return nil
}

func (s *wsSession) handle(env *protocol.Envelope) {
	switch env.Type {
	case protocol.TypeOpen:
		s.handleOpen(env)
	case protocol.TypeData:
		s.handleData(env)
	case protocol.TypeClose:
		s.handleClose(env)
	case protocol.TypePing:
		_ = s.send(&protocol.Envelope{Type: protocol.TypePong, Ts: env.Ts})
	case protocol.TypePong:
		// heartbeat ack
	default:
		_ = s.send(&protocol.Envelope{Type: protocol.TypeError, ConnID: env.ConnID, Reason: "unknown message type"})
	}
}

func (s *wsSession) handleOpen(env *protocol.Envelope) {
	if env.ConnID == "" || env.DstVIP == "" || env.DstPort == 0 {
		_ = s.send(&protocol.Envelope{Type: protocol.TypeOpenResp, ConnID: env.ConnID, Ok: false, Reason: "invalid open request"})
		return
	}
	if _, exists := s.conns.Get(env.ConnID); exists {
		_ = s.send(&protocol.Envelope{Type: protocol.TypeOpenResp, ConnID: env.ConnID, Ok: false, Reason: "duplicate conn_id"})
		return
	}
	realIP, err := s.resolveTargetIP(env.DstVIP)
	if err != nil {
		_ = s.send(&protocol.Envelope{Type: protocol.TypeOpenResp, ConnID: env.ConnID, Ok: false, Reason: err.Error()})
		return
	}
	dst := net.JoinHostPort(realIP, strconv.Itoa(int(env.DstPort)))
	remote, err := net.DialTimeout("tcp", dst, s.cfg.dialTimeout())
	if err != nil {
		_ = s.send(&protocol.Envelope{Type: protocol.TypeOpenResp, ConnID: env.ConnID, Ok: false, Reason: "dial failed: " + err.Error()})
		return
	}
	s.conns.Set(env.ConnID, remote)
	_ = s.send(&protocol.Envelope{Type: protocol.TypeOpenResp, ConnID: env.ConnID, Ok: true})
	log.Printf("open conn agent=%s conn_id=%s vip=%s:%d real=%s", s.agent, env.ConnID, env.DstVIP, env.DstPort, dst)

	go s.pipeRemoteToAgent(env.ConnID, remote)
}

func (s *wsSession) resolveTargetIP(dstVIP string) (string, error) {
	if realIP, ok := s.cfg.Network.VIPMap[dstVIP]; ok {
		return realIP, nil
	}
	if !s.cfg.Network.AllowDirectIPFallback {
		return "", errors.New("vip not mapped")
	}
	ip := net.ParseIP(dstVIP)
	if ip == nil || ip.To4() == nil {
		return "", errors.New("invalid direct ip")
	}
	return dstVIP, nil
}

func (s *wsSession) handleData(env *protocol.Envelope) {
	remote, ok := s.conns.Get(env.ConnID)
	if !ok {
		_ = s.send(&protocol.Envelope{Type: protocol.TypeClose, ConnID: env.ConnID, Reason: "conn not found"})
		return
	}
	if len(env.Payload) == 0 {
		return
	}
	_ = remote.SetWriteDeadline(time.Now().Add(s.cfg.writeTimeout()))
	if _, err := remote.Write(env.Payload); err != nil {
		log.Printf("remote write error conn_id=%s err=%v", env.ConnID, err)
		s.closeConn(env.ConnID)
		_ = s.send(&protocol.Envelope{Type: protocol.TypeClose, ConnID: env.ConnID, Reason: "remote write failed"})
	}
}

func (s *wsSession) handleClose(env *protocol.Envelope) {
	s.closeConn(env.ConnID)
}

func (s *wsSession) pipeRemoteToAgent(connID string, remote net.Conn) {
	buf := make([]byte, 32*1024)
	var seq uint32
	for {
		_ = remote.SetReadDeadline(time.Now().Add(s.cfg.idleTimeout()))
		n, err := remote.Read(buf)
		if n > 0 {
			seq++
			payload := make([]byte, n)
			copy(payload, buf[:n])
			if sendErr := s.send(&protocol.Envelope{Type: protocol.TypeData, ConnID: connID, Payload: payload, Seq: seq}); sendErr != nil {
				log.Printf("send data to agent failed conn_id=%s err=%v", connID, sendErr)
				s.closeConn(connID)
				return
			}
		}
		if err != nil {
			s.closeConn(connID)
			_ = s.send(&protocol.Envelope{Type: protocol.TypeClose, ConnID: connID, Reason: "remote closed"})
			return
		}
	}
}

func (s *wsSession) send(env *protocol.Envelope) error {
	data, err := protocol.Marshal(env)
	if err != nil {
		return err
	}
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	_ = s.conn.SetWriteDeadline(time.Now().Add(s.cfg.writeTimeout()))
	return websocket.Message.Send(s.conn, data)
}

func (s *wsSession) closeConn(connID string) {
	remote, ok := s.conns.Get(connID)
	if ok {
		_ = remote.Close()
		s.conns.Delete(connID)
	}
}

func (s *wsSession) closeAll() {
	s.conns.Range(func(connID string, remote net.Conn) {
		_ = remote.Close()
		s.conns.Delete(connID)
	})
}
