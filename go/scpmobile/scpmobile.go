// Package scpmobile is the networking core of scp-android, bound to Kotlin
// with gomobile. See docs/design.md.
//
// The exported surface is restricted to gomobile-bindable shapes: functions
// return T, error, or (T, error); callbacks are interfaces implemented in Kotlin.
package scpmobile

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/crypto/ssh"
)

const (
	defaultDialTimeout = 10 * time.Second
	termType           = "xterm-256color"
	keyComment         = "scp-android"
)

// HostKeyVerifier is implemented in Kotlin. Verify blocks the SSH handshake
// until the user decides; it returns true to trust the key. The alias is the
// server's real host:port, never the dialed address.
type HostKeyVerifier interface {
	Verify(alias, keyType, fingerprint string, authorizedKey []byte) bool
}

// OutputSink receives shell output and exactly one close notification.
type OutputSink interface {
	Write(p []byte)
	Closed(reason string)
}

// ---- keys ----

// KeyPair is a device SSH identity.
type KeyPair struct {
	private []byte
	public  []byte
}

// PrivateOpenSSH returns the private key as an OpenSSH PEM block.
func (k *KeyPair) PrivateOpenSSH() []byte { return k.private }

// PublicAuthorizedKey returns a single authorized_keys line (no trailing newline).
func (k *KeyPair) PublicAuthorizedKey() []byte { return k.public }

// GenerateEd25519 creates a new Ed25519 device key.
func GenerateEd25519() (*KeyPair, error) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("generate ed25519 key: %w", err)
	}
	block, err := ssh.MarshalPrivateKey(priv, keyComment)
	if err != nil {
		return nil, fmt.Errorf("marshal private key: %w", err)
	}
	sshPub, err := ssh.NewPublicKey(pub)
	if err != nil {
		return nil, fmt.Errorf("marshal public key: %w", err)
	}
	return &KeyPair{
		private: pem.EncodeToMemory(block),
		public:  authorizedLine(sshPub),
	}, nil
}

// Fingerprint returns the OpenSSH SHA256 fingerprint ("SHA256:…") of an
// authorized_keys line.
func Fingerprint(authorizedKey []byte) (string, error) {
	pub, _, _, _, err := ssh.ParseAuthorizedKey(authorizedKey)
	if err != nil {
		return "", fmt.Errorf("parse authorized key: %w", err)
	}
	return ssh.FingerprintSHA256(pub), nil
}

func authorizedLine(k ssh.PublicKey) []byte {
	return []byte(strings.TrimRight(string(ssh.MarshalAuthorizedKey(k)), "\n"))
}

// ---- transport ----

// Conn is an opaque transport (direct TCP or a Boundary session) handed to OpenShell.
type Conn struct {
	c net.Conn
}

// errUninitialized is returned by methods on zero values. gomobile generates
// public no-arg constructors, so Kotlin can create these; a Go panic would
// crash the whole app.
var errUninitialized = errors.New("object not initialized: obtain it from a scpmobile function")

// Close closes the underlying transport.
func (c *Conn) Close() error {
	if c == nil || c.c == nil {
		return errUninitialized
	}
	return c.c.Close()
}

// DialDirect opens a TCP connection to host:port (routed over the VPN when up).
// timeoutMs <= 0 uses the default timeout.
func DialDirect(host string, port int, timeoutMs int) (*Conn, error) {
	if strings.TrimSpace(host) == "" {
		return nil, errors.New("dial: host is empty")
	}
	if port < 1 || port > 65535 {
		return nil, fmt.Errorf("dial: invalid port %d", port)
	}
	timeout := defaultDialTimeout
	if timeoutMs > 0 {
		timeout = time.Duration(timeoutMs) * time.Millisecond
	}
	c, err := net.DialTimeout("tcp", net.JoinHostPort(host, strconv.Itoa(port)), timeout)
	if err != nil {
		return nil, fmt.Errorf("dial %s:%d: %w", host, port, err)
	}
	return &Conn{c: c}, nil
}

// ---- shell ----

// Shell is an interactive PTY shell over SSH.
type Shell struct {
	client    *ssh.Client
	session   *ssh.Session
	stdin     io.WriteCloser
	out       OutputSink
	closed    atomic.Bool
	notifyOne sync.Once
}

// OpenShell performs the SSH handshake on conn, asks hk to verify the host key
// (keyed by alias), authenticates with privateKey, and starts a PTY shell.
// Output is delivered to out. On any error conn is closed.
func OpenShell(conn *Conn, user string, privateKey []byte, alias string,
	hk HostKeyVerifier, out OutputSink, cols, rows int) (*Shell, error) {
	if conn == nil || conn.c == nil {
		return nil, errors.New("open shell: conn is nil")
	}
	fail := func(err error) (*Shell, error) {
		conn.c.Close()
		return nil, err
	}
	if hk == nil {
		return fail(errors.New("open shell: host key verifier is required"))
	}
	if out == nil {
		return fail(errors.New("open shell: output sink is required"))
	}
	signer, err := ssh.ParsePrivateKey(privateKey)
	if err != nil {
		return fail(fmt.Errorf("open shell: parse private key: %w", err))
	}

	cfg := &ssh.ClientConfig{
		User: user,
		Auth: []ssh.AuthMethod{ssh.PublicKeys(signer)},
		HostKeyCallback: func(_ string, _ net.Addr, key ssh.PublicKey) error {
			if !hk.Verify(alias, key.Type(), ssh.FingerprintSHA256(key), authorizedLine(key)) {
				return fmt.Errorf("host key for %s not trusted", alias)
			}
			return nil
		},
	}
	cc, chans, reqs, err := ssh.NewClientConn(conn.c, alias, cfg)
	if err != nil {
		return fail(fmt.Errorf("ssh handshake with %s: %w", alias, err))
	}
	client := ssh.NewClient(cc, chans, reqs)

	sess, err := client.NewSession()
	if err != nil {
		client.Close()
		return nil, fmt.Errorf("open session: %w", err)
	}
	s := &Shell{client: client, session: sess, out: out}
	if err := s.start(cols, rows); err != nil {
		sess.Close()
		client.Close()
		return nil, err
	}
	return s, nil
}

func (s *Shell) start(cols, rows int) error {
	modes := ssh.TerminalModes{ssh.ECHO: 1, ssh.TTY_OP_ISPEED: 38400, ssh.TTY_OP_OSPEED: 38400}
	if err := s.session.RequestPty(termType, rows, cols, modes); err != nil {
		return fmt.Errorf("request pty: %w", err)
	}
	stdin, err := s.session.StdinPipe()
	if err != nil {
		return fmt.Errorf("stdin pipe: %w", err)
	}
	stdout, err := s.session.StdoutPipe()
	if err != nil {
		return fmt.Errorf("stdout pipe: %w", err)
	}
	s.session.Stderr = sinkWriter{s.out}
	s.stdin = stdin
	if err := s.session.Shell(); err != nil {
		return fmt.Errorf("start shell: %w", err)
	}
	go s.pump(stdout)
	return nil
}

func (s *Shell) pump(stdout io.Reader) {
	_, _ = io.Copy(sinkWriter{s.out}, stdout)
	reason := "session ended"
	if err := s.session.Wait(); err != nil && !s.closed.Load() {
		reason = err.Error()
	}
	if s.closed.Load() {
		reason = "closed"
	}
	s.notifyClosed(reason)
	s.client.Close()
}

func (s *Shell) notifyClosed(reason string) {
	s.notifyOne.Do(func() { s.out.Closed(reason) })
}

func (s *Shell) initialized() bool {
	return s != nil && s.client != nil && s.session != nil && s.stdin != nil
}

// Write sends input to the remote shell.
func (s *Shell) Write(p []byte) error {
	if !s.initialized() {
		return errUninitialized
	}
	if s.closed.Load() {
		return errors.New("shell is closed")
	}
	_, err := s.stdin.Write(p)
	return err
}

// Resize sends a window-change request.
func (s *Shell) Resize(cols, rows int) error {
	if !s.initialized() {
		return errUninitialized
	}
	if s.closed.Load() {
		return errors.New("shell is closed")
	}
	return s.session.WindowChange(rows, cols)
}

// Close ends the shell and the SSH connection. It is idempotent; the sink is
// notified exactly once.
func (s *Shell) Close() error {
	if !s.initialized() {
		return errUninitialized
	}
	if !s.closed.CompareAndSwap(false, true) {
		return nil
	}
	s.session.Close()
	err := s.client.Close()
	s.notifyClosed("closed")
	if errors.Is(err, net.ErrClosed) {
		return nil
	}
	return err
}

// sinkWriter adapts OutputSink to io.Writer, copying each chunk so Kotlin
// never observes a reused buffer.
type sinkWriter struct{ out OutputSink }

func (w sinkWriter) Write(p []byte) (int, error) {
	if len(p) > 0 {
		w.out.Write(append([]byte(nil), p...))
	}
	return len(p), nil
}
