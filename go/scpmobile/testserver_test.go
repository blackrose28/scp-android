package scpmobile

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/binary"
	"net"
	"sync"
	"testing"

	"golang.org/x/crypto/ssh"
)

// ptyReq mirrors RFC 4254 §6.2 "pty-req" payload.
type ptyReq struct {
	Term     string
	Cols     uint32
	Rows     uint32
	WidthPx  uint32
	HeightPx uint32
	Modes    string
}

// testServer is an in-process SSH server that accepts one authorized key,
// records pty/window-change requests, and echoes shell input back.
type testServer struct {
	t        *testing.T
	ln       net.Listener
	hostKey  ssh.PublicKey
	mu       sync.Mutex
	pty      *ptyReq
	resizes  [][2]uint32
	authUser string
	resized  chan struct{}
}

func newTestServer(t *testing.T, authorized ssh.PublicKey) *testServer {
	t.Helper()
	_, hostPriv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	signer, err := ssh.NewSignerFromKey(hostPriv)
	if err != nil {
		t.Fatal(err)
	}
	s := &testServer{t: t, hostKey: signer.PublicKey(), resized: make(chan struct{}, 8)}
	cfg := &ssh.ServerConfig{
		PublicKeyCallback: func(md ssh.ConnMetadata, key ssh.PublicKey) (*ssh.Permissions, error) {
			if authorized != nil && string(key.Marshal()) == string(authorized.Marshal()) {
				s.mu.Lock()
				s.authUser = md.User()
				s.mu.Unlock()
				return &ssh.Permissions{}, nil
			}
			return nil, errUnauthorized
		},
	}
	cfg.AddHostKey(signer)
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	s.ln = ln
	t.Cleanup(func() { ln.Close() })
	go s.serve(cfg)
	return s
}

var errUnauthorized = &net.AddrError{Err: "unauthorized"}

func (s *testServer) addr() (string, int) {
	a := s.ln.Addr().(*net.TCPAddr)
	return a.IP.String(), a.Port
}

func (s *testServer) serve(cfg *ssh.ServerConfig) {
	for {
		c, err := s.ln.Accept()
		if err != nil {
			return
		}
		go s.handle(c, cfg)
	}
}

func (s *testServer) handle(c net.Conn, cfg *ssh.ServerConfig) {
	_, chans, reqs, err := ssh.NewServerConn(c, cfg)
	if err != nil {
		c.Close()
		return
	}
	go ssh.DiscardRequests(reqs)
	for nc := range chans {
		if nc.ChannelType() != "session" {
			nc.Reject(ssh.UnknownChannelType, "only session")
			continue
		}
		ch, creqs, err := nc.Accept()
		if err != nil {
			continue
		}
		go func() {
			for r := range creqs {
				switch r.Type {
				case "pty-req":
					var p ptyReq
					if err := ssh.Unmarshal(r.Payload, &p); err == nil {
						s.mu.Lock()
						s.pty = &p
						s.mu.Unlock()
					}
					r.Reply(true, nil)
				case "window-change":
					if len(r.Payload) >= 8 {
						cols := binary.BigEndian.Uint32(r.Payload[0:4])
						rows := binary.BigEndian.Uint32(r.Payload[4:8])
						s.mu.Lock()
						s.resizes = append(s.resizes, [2]uint32{cols, rows})
						s.mu.Unlock()
						s.resized <- struct{}{}
					}
				case "shell":
					r.Reply(true, nil)
					go func() {
						buf := make([]byte, 1024)
						for {
							n, err := ch.Read(buf)
							if n > 0 {
								ch.Write(buf[:n])
							}
							if err != nil {
								ch.Close()
								return
							}
						}
					}()
				default:
					r.Reply(false, nil)
				}
			}
		}()
	}
}
