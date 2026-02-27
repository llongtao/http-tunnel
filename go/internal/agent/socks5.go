package agent

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"net"
	"strconv"
	"time"
)

type SocksServer struct {
	listenAddr string
	session    *Session

	ln net.Listener
}

func NewSocksServer(listenAddr string, session *Session) *SocksServer {
	return &SocksServer{listenAddr: listenAddr, session: session}
}

func (s *SocksServer) Run(ctx context.Context) error {
	ln, err := net.Listen("tcp", s.listenAddr)
	if err != nil {
		return fmt.Errorf("listen socks5 %s: %w", s.listenAddr, err)
	}
	s.ln = ln
	log.Printf("socks5 listen on %s", s.listenAddr)

	go func() {
		<-ctx.Done()
		_ = ln.Close()
	}()

	for {
		conn, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			log.Printf("socks5 accept error: %v", err)
			continue
		}
		go s.handleConn(ctx, conn)
	}
}

func (s *SocksServer) handleConn(ctx context.Context, c net.Conn) {
	defer c.Close()
	if err := s.handshake(c); err != nil {
		log.Printf("socks5 handshake failed: %v", err)
		return
	}

	dstHost, dstPort, err := s.readConnectRequest(c)
	if err != nil {
		log.Printf("socks5 read request failed: %v", err)
		_ = writeSocksReply(c, 0x01)
		return
	}

	openCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	stream, err := s.session.OpenStream(openCtx, dstHost, uint32(dstPort), c.RemoteAddr().String())
	if err != nil {
		log.Printf("open stream failed vip=%s:%d err=%v", dstHost, dstPort, err)
		_ = writeSocksReply(c, 0x05)
		return
	}
	defer stream.Close()

	if err := writeSocksReply(c, 0x00); err != nil {
		return
	}

	copyDone := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(stream, c)
		_ = stream.Close()
		copyDone <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(c, stream)
		_ = c.Close()
		copyDone <- struct{}{}
	}()
	<-copyDone
}

func (s *SocksServer) handshake(c net.Conn) error {
	header := make([]byte, 2)
	if _, err := io.ReadFull(c, header); err != nil {
		return err
	}
	if header[0] != 0x05 {
		return fmt.Errorf("unsupported socks version %d", header[0])
	}
	nMethods := int(header[1])
	methods := make([]byte, nMethods)
	if _, err := io.ReadFull(c, methods); err != nil {
		return err
	}
	_, err := c.Write([]byte{0x05, 0x00})
	return err
}

func (s *SocksServer) readConnectRequest(c net.Conn) (string, int, error) {
	head := make([]byte, 4)
	if _, err := io.ReadFull(c, head); err != nil {
		return "", 0, err
	}
	if head[0] != 0x05 {
		return "", 0, fmt.Errorf("bad socks version")
	}
	if head[1] != 0x01 {
		return "", 0, fmt.Errorf("only CONNECT is supported")
	}

	atyp := head[3]
	var host string
	switch atyp {
	case 0x01:
		addr := make([]byte, 4)
		if _, err := io.ReadFull(c, addr); err != nil {
			return "", 0, err
		}
		host = net.IP(addr).String()
	case 0x03:
		l := make([]byte, 1)
		if _, err := io.ReadFull(c, l); err != nil {
			return "", 0, err
		}
		domain := make([]byte, int(l[0]))
		if _, err := io.ReadFull(c, domain); err != nil {
			return "", 0, err
		}
		host = string(domain)
	case 0x04:
		addr := make([]byte, 16)
		if _, err := io.ReadFull(c, addr); err != nil {
			return "", 0, err
		}
		host = net.IP(addr).String()
	default:
		return "", 0, fmt.Errorf("unsupported atyp=%d", atyp)
	}

	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(c, portBuf); err != nil {
		return "", 0, err
	}
	port := int(binary.BigEndian.Uint16(portBuf))
	if _, err := strconv.Atoi(fmt.Sprintf("%d", port)); err != nil {
		return "", 0, err
	}
	return host, port, nil
}

func writeSocksReply(c net.Conn, rep byte) error {
	resp := []byte{0x05, rep, 0x00, 0x01, 0, 0, 0, 0, 0, 0}
	_, err := c.Write(resp)
	return err
}
