package scpmobile

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strings"
	"sync"
	"time"

	"github.com/hashicorp/boundary/api"
	"github.com/hashicorp/boundary/api/proxy"
	"github.com/hashicorp/boundary/api/targets"
)

const (
	authorizeTimeout = 30 * time.Second
	closeWait        = 10 * time.Second
)

// BoundarySession is an authorized Boundary session proxied in-process. The
// SSH client consumes Conn directly; no loopback port is ever opened.
type BoundarySession struct {
	conn    *Conn
	proxy   *proxy.ClientProxy
	cancel  context.CancelFunc
	ln      *singleConnListener
	done    chan struct{} // closed when proxy.Start returns
	closeMu sync.Mutex
	closed  bool
}

// DialBoundary authorizes a session on targetID (ttcp_…) with the Boundary
// auth token and starts the client proxy over an in-memory listener.
func DialBoundary(controllerURL, token, targetID string) (*BoundarySession, error) {
	base, err := normalizeControllerURL(controllerURL)
	if err != nil {
		return nil, err
	}
	token = strings.TrimSpace(token)
	if token == "" {
		return nil, errors.New("boundary: auth token is empty")
	}
	targetID = strings.TrimSpace(targetID)
	if targetID == "" {
		return nil, errors.New("boundary: target ID is empty")
	}

	cfg, err := api.DefaultConfig()
	if err != nil {
		return nil, fmt.Errorf("boundary: api config: %w", err)
	}
	// The address and token are always the caller's, never BOUNDARY_ADDR /
	// BOUNDARY_TOKEN. (Other BOUNDARY_* variables are still read by the api
	// library; an Android app has none set.)
	cfg.Addr = base
	cfg.Token = token
	client, err := api.NewClient(cfg)
	if err != nil {
		return nil, fmt.Errorf("boundary: api client: %w", err)
	}
	client.SetToken(token)

	authCtx, authCancel := context.WithTimeout(context.Background(), authorizeTimeout)
	defer authCancel()
	authz, err := targets.NewClient(client).AuthorizeSession(authCtx, targetID)
	if err != nil {
		return nil, fmt.Errorf("boundary: authorize session on %s: %w", targetID, err)
	}
	if authz == nil || authz.Item == nil || authz.Item.AuthorizationToken == "" {
		return nil, fmt.Errorf("boundary: authorize session on %s: no authorization token returned", targetID)
	}

	clientEnd, ln := newSingleConnListener()
	ctx, cancel := context.WithCancel(context.Background())
	p, err := proxy.New(ctx, authz.Item.AuthorizationToken,
		proxy.WithListener(ln), proxy.WithApiClient(client))
	if err != nil {
		cancel()
		ln.Close()
		clientEnd.Close()
		return nil, fmt.Errorf("boundary: create proxy: %w", err)
	}
	ln.onConnClosed = cancel // set before Start: the SSH conn closing ends the session
	s := &BoundarySession{
		conn:   &Conn{c: clientEnd},
		proxy:  p,
		cancel: cancel,
		ln:     ln,
		done:   make(chan struct{}),
	}
	go func() {
		defer close(s.done)
		_ = p.Start()
	}()
	return s, nil
}

// Conn returns the in-process transport to hand to OpenShell.
func (s *BoundarySession) Conn() (*Conn, error) {
	if s == nil || s.conn == nil {
		return nil, errUninitialized
	}
	return s.conn, nil
}

// ExpirationUnixMs is when the session expires, in Unix milliseconds.
func (s *BoundarySession) ExpirationUnixMs() int64 {
	if s == nil || s.proxy == nil {
		return 0
	}
	return s.proxy.SessionExpiration().UnixMilli()
}

// ConnectionsLeft is how many more connections the session allows.
func (s *BoundarySession) ConnectionsLeft() int {
	if s == nil || s.proxy == nil {
		return 0
	}
	return int(s.proxy.ConnectionsLeft())
}

// Close ends the connection and the Boundary session. It is idempotent. The
// session also ends on its own when the SSH connection over Conn closes.
func (s *BoundarySession) Close() error {
	if s == nil || s.proxy == nil {
		return errUninitialized
	}
	s.closeMu.Lock()
	if s.closed {
		s.closeMu.Unlock()
		return nil
	}
	s.closed = true
	s.closeMu.Unlock()

	s.conn.Close()
	s.cancel()
	s.ln.Close()
	select {
	case <-s.done:
	case <-time.After(closeWait):
	}
	return nil
}

// singleConnListener is a net.Listener that yields exactly one pre-made
// connection and then blocks until closed, so the proxy serves one in-process
// client without binding any port.
type singleConnListener struct {
	mu       sync.Mutex
	pending  net.Conn // server end, until accepted
	closedCh chan struct{}
	once     sync.Once
	// onConnClosed, if set before Accept, runs once when the accepted
	// connection closes. DialBoundary uses it to cancel the proxy, because
	// ClientProxy.Start only tears the session down once its context ends.
	onConnClosed func()
}

// newSingleConnListener returns the client end of an in-memory connection and
// a listener that will accept its server end.
func newSingleConnListener() (net.Conn, *singleConnListener) {
	client, server := net.Pipe()
	return client, &singleConnListener{pending: server, closedCh: make(chan struct{})}
}

func (l *singleConnListener) Accept() (net.Conn, error) {
	l.mu.Lock()
	c := l.pending
	l.pending = nil
	l.mu.Unlock()
	if c != nil {
		return &listenerConn{Conn: c, onClose: func() {
			l.Close()
			if l.onConnClosed != nil {
				l.onConnClosed()
			}
		}}, nil
	}
	<-l.closedCh
	return nil, net.ErrClosed
}

func (l *singleConnListener) Close() error {
	l.once.Do(func() {
		l.mu.Lock()
		if l.pending != nil {
			l.pending.Close() // never accepted: unblock the client end
			l.pending = nil
		}
		l.mu.Unlock()
		close(l.closedCh)
	})
	return nil
}

// listenerConn closes its listener when it is closed. The listener serves
// exactly one connection, so once the SSH client is done the proxy ends and
// tears the Boundary session down, even if the caller only closes the shell.
type listenerConn struct {
	net.Conn
	once    sync.Once
	onClose func()
}

func (c *listenerConn) Close() error {
	err := c.Conn.Close()
	c.once.Do(c.onClose)
	return err
}

type memAddr struct{}

func (memAddr) Network() string { return "scpmobile-mem" }
func (memAddr) String() string  { return "scpmobile-mem" }

func (l *singleConnListener) Addr() net.Addr { return memAddr{} }
