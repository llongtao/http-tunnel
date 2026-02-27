package agent

import (
	"errors"
	"io"
	"net"
	"sync"
	"time"

	"htunnel/go/internal/protocol"
)

type wsStream struct {
	id      string
	session *Session

	inCh    chan []byte
	closeCh chan struct{}

	once sync.Once

	rmu  sync.Mutex
	rbuf []byte
}

func newWSStream(id string, session *Session) *wsStream {
	return &wsStream{
		id:      id,
		session: session,
		inCh:    make(chan []byte, 128),
		closeCh: make(chan struct{}),
	}
}

func (s *wsStream) Read(p []byte) (int, error) {
	for {
		s.rmu.Lock()
		if len(s.rbuf) > 0 {
			n := copy(p, s.rbuf)
			s.rbuf = s.rbuf[n:]
			s.rmu.Unlock()
			return n, nil
		}
		s.rmu.Unlock()

		select {
		case <-s.closeCh:
			return 0, io.EOF
		case data, ok := <-s.inCh:
			if !ok {
				return 0, io.EOF
			}
			s.rmu.Lock()
			s.rbuf = data
			s.rmu.Unlock()
		}
	}
}

func (s *wsStream) Write(p []byte) (int, error) {
	select {
	case <-s.closeCh:
		return 0, io.EOF
	default:
	}
	if len(p) == 0 {
		return 0, nil
	}
	payload := make([]byte, len(p))
	copy(payload, p)
	err := s.session.send(&protocol.Envelope{Type: protocol.TypeData, ConnID: s.id, Payload: payload})
	if err != nil {
		return 0, err
	}
	return len(p), nil
}

func (s *wsStream) Close() error {
	s.once.Do(func() {
		close(s.closeCh)
		s.session.onLocalClose(s.id)
	})
	return nil
}

func (s *wsStream) closeRemote() {
	s.once.Do(func() {
		close(s.closeCh)
	})
}

func (s *wsStream) push(data []byte) error {
	if len(data) == 0 {
		return nil
	}
	payload := make([]byte, len(data))
	copy(payload, data)
	select {
	case <-s.closeCh:
		return io.EOF
	case s.inCh <- payload:
		return nil
	default:
		return errors.New("stream inbound buffer full")
	}
}

func (s *wsStream) LocalAddr() net.Addr  { return dummyAddr("ws-local") }
func (s *wsStream) RemoteAddr() net.Addr { return dummyAddr("ws-remote") }

func (s *wsStream) SetDeadline(t time.Time) error {
	_ = t
	return nil
}

func (s *wsStream) SetReadDeadline(t time.Time) error {
	_ = t
	return nil
}

func (s *wsStream) SetWriteDeadline(t time.Time) error {
	_ = t
	return nil
}

type dummyAddr string

func (d dummyAddr) Network() string { return "tcp" }
func (d dummyAddr) String() string  { return string(d) }
