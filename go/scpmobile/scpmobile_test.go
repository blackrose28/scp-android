package scpmobile

import (
	"bytes"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"
)

// ---- test doubles for Kotlin-side callbacks ----

type recordingVerifier struct {
	mu          sync.Mutex
	calls       int
	alias       string
	keyType     string
	fingerprint string
	authKey     []byte
	trust       bool
}

func (v *recordingVerifier) Verify(alias, keyType, fingerprint string, authorizedKey []byte) bool {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.calls++
	v.alias, v.keyType, v.fingerprint, v.authKey = alias, keyType, fingerprint, authorizedKey
	return v.trust
}

type bufferSink struct {
	mu     sync.Mutex
	buf    bytes.Buffer
	closed chan string
}

func newBufferSink() *bufferSink { return &bufferSink{closed: make(chan string, 1)} }

func (b *bufferSink) Write(p []byte) {
	b.mu.Lock()
	b.buf.Write(p)
	b.mu.Unlock()
}
func (b *bufferSink) Closed(reason string) { b.closed <- reason }
func (b *bufferSink) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

// ---- keys ----

func TestGenerateEd25519ProducesUsableKeyPair(t *testing.T) {
	kp, err := GenerateEd25519()
	if err != nil {
		t.Fatalf("GenerateEd25519: %v", err)
	}
	signer, err := ssh.ParsePrivateKey(kp.PrivateOpenSSH())
	if err != nil {
		t.Fatalf("private key is not OpenSSH-parseable: %v", err)
	}
	pub, _, _, _, err := ssh.ParseAuthorizedKey(kp.PublicAuthorizedKey())
	if err != nil {
		t.Fatalf("public key is not authorized_keys format: %v", err)
	}
	if pub.Type() != ssh.KeyAlgoED25519 {
		t.Fatalf("key type = %s, want ed25519", pub.Type())
	}
	if !bytes.Equal(signer.PublicKey().Marshal(), pub.Marshal()) {
		t.Fatal("public key does not match private key")
	}
}

func TestGenerateEd25519IsUniquePerCall(t *testing.T) {
	a, err := GenerateEd25519()
	if err != nil {
		t.Fatal(err)
	}
	b, err := GenerateEd25519()
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Equal(a.PublicAuthorizedKey(), b.PublicAuthorizedKey()) {
		t.Fatal("two generated keys are identical")
	}
}

func TestFingerprintMatchesOpenSSHFormat(t *testing.T) {
	kp, err := GenerateEd25519()
	if err != nil {
		t.Fatal(err)
	}
	pub, _, _, _, _ := ssh.ParseAuthorizedKey(kp.PublicAuthorizedKey())
	got, err := Fingerprint(kp.PublicAuthorizedKey())
	if err != nil {
		t.Fatalf("Fingerprint: %v", err)
	}
	if want := ssh.FingerprintSHA256(pub); got != want {
		t.Fatalf("Fingerprint = %q, want %q", got, want)
	}
}

func TestFingerprintRejectsGarbage(t *testing.T) {
	if _, err := Fingerprint([]byte("not a key")); err == nil {
		t.Fatal("expected error for invalid authorized key")
	}
}

// ---- zero values (gomobile exposes public no-arg constructors) ----

func TestZeroValueTypesReturnErrorsInsteadOfPanicking(t *testing.T) {
	defer func() {
		if r := recover(); r != nil {
			t.Fatalf("zero-value method panicked (would crash the Android app): %v", r)
		}
	}()
	if err := (&Conn{}).Close(); err == nil {
		t.Error("Conn{}.Close: expected error")
	}
	sh := &Shell{}
	if err := sh.Write([]byte("x")); err == nil {
		t.Error("Shell{}.Write: expected error")
	}
	if err := sh.Resize(80, 24); err == nil {
		t.Error("Shell{}.Resize: expected error")
	}
	if err := sh.Close(); err == nil {
		t.Error("Shell{}.Close: expected error")
	}
	kp := &KeyPair{}
	if kp.PrivateOpenSSH() != nil || kp.PublicAuthorizedKey() != nil {
		t.Error("KeyPair{}: expected nil keys")
	}
	if _, err := OpenShell(&Conn{}, "u", nil, "a:22", &recordingVerifier{}, newBufferSink(), 80, 24); err == nil {
		t.Error("OpenShell(Conn{}): expected error")
	}
}

// ---- direct dial ----

func TestDialDirectConnects(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	accepted := make(chan struct{})
	go func() {
		if c, err := ln.Accept(); err == nil {
			close(accepted)
			c.Close()
		}
	}()
	a := ln.Addr().(*net.TCPAddr)
	c, err := DialDirect(a.IP.String(), a.Port, 2000)
	if err != nil {
		t.Fatalf("DialDirect: %v", err)
	}
	defer c.Close()
	select {
	case <-accepted:
	case <-time.After(2 * time.Second):
		t.Fatal("server never accepted connection")
	}
}

func TestDialDirectRefusedReturnsError(t *testing.T) {
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	port := ln.Addr().(*net.TCPAddr).Port
	ln.Close()
	if _, err := DialDirect("127.0.0.1", port, 2000); err == nil {
		t.Fatal("expected error dialing closed port")
	}
}

func TestDialDirectRejectsInvalidPort(t *testing.T) {
	for _, p := range []int{0, -1, 65536} {
		if _, err := DialDirect("127.0.0.1", p, 1000); err == nil {
			t.Fatalf("expected error for port %d", p)
		}
	}
}

// ---- shell + host key verification ----

func setupShellServer(t *testing.T) (*testServer, *KeyPair) {
	t.Helper()
	kp, err := GenerateEd25519()
	if err != nil {
		t.Fatal(err)
	}
	pub, _, _, _, _ := ssh.ParseAuthorizedKey(kp.PublicAuthorizedKey())
	return newTestServer(t, pub), kp
}

func TestOpenShellAsksVerifierWithAliasAndFingerprint(t *testing.T) {
	srv, kp := setupShellServer(t)
	host, port := srv.addr()
	conn, err := DialDirect(host, port, 2000)
	if err != nil {
		t.Fatal(err)
	}
	v := &recordingVerifier{trust: true}
	sh, err := OpenShell(conn, "alice", kp.PrivateOpenSSH(), "db1.internal:22", v, newBufferSink(), 80, 24)
	if err != nil {
		t.Fatalf("OpenShell: %v", err)
	}
	defer sh.Close()

	if v.calls != 1 {
		t.Fatalf("verifier calls = %d, want 1", v.calls)
	}
	if v.alias != "db1.internal:22" {
		t.Fatalf("alias = %q, want the server alias, not the dialed address", v.alias)
	}
	if v.keyType != ssh.KeyAlgoED25519 {
		t.Fatalf("keyType = %q", v.keyType)
	}
	if want := ssh.FingerprintSHA256(srv.hostKey); v.fingerprint != want {
		t.Fatalf("fingerprint = %q, want %q", v.fingerprint, want)
	}
	if !strings.HasPrefix(string(v.authKey), "ssh-ed25519 ") {
		t.Fatalf("authorizedKey = %q, want authorized_keys line", v.authKey)
	}
}

func TestOpenShellRejectedHostKeyFailsBeforeAuth(t *testing.T) {
	srv, kp := setupShellServer(t)
	host, port := srv.addr()
	conn, err := DialDirect(host, port, 2000)
	if err != nil {
		t.Fatal(err)
	}
	v := &recordingVerifier{trust: false}
	_, err = OpenShell(conn, "alice", kp.PrivateOpenSSH(), "db1.internal:22", v, newBufferSink(), 80, 24)
	if err == nil {
		t.Fatal("expected error when host key is not trusted")
	}
	if !strings.Contains(err.Error(), "host key") {
		t.Fatalf("error = %v, want host key rejection", err)
	}
	srv.mu.Lock()
	defer srv.mu.Unlock()
	if srv.authUser != "" {
		t.Fatal("client authenticated even though host key was rejected")
	}
}

func TestOpenShellRequiresVerifier(t *testing.T) {
	srv, kp := setupShellServer(t)
	host, port := srv.addr()
	conn, _ := DialDirect(host, port, 2000)
	if _, err := OpenShell(conn, "alice", kp.PrivateOpenSSH(), "a:22", nil, newBufferSink(), 80, 24); err == nil {
		t.Fatal("expected error with nil verifier (must never auto-trust)")
	}
}

func TestOpenShellRejectsBadPrivateKey(t *testing.T) {
	srv, _ := setupShellServer(t)
	host, port := srv.addr()
	conn, _ := DialDirect(host, port, 2000)
	v := &recordingVerifier{trust: true}
	if _, err := OpenShell(conn, "alice", []byte("garbage"), "a:22", v, newBufferSink(), 80, 24); err == nil {
		t.Fatal("expected error for invalid private key")
	}
}

func TestOpenShellRequestsPtyAndEchoes(t *testing.T) {
	srv, kp := setupShellServer(t)
	host, port := srv.addr()
	conn, _ := DialDirect(host, port, 2000)
	out := newBufferSink()
	sh, err := OpenShell(conn, "alice", kp.PrivateOpenSSH(), "a:22", &recordingVerifier{trust: true}, out, 120, 40)
	if err != nil {
		t.Fatalf("OpenShell: %v", err)
	}
	defer sh.Close()

	srv.mu.Lock()
	pty, user := srv.pty, srv.authUser
	srv.mu.Unlock()
	if user != "alice" {
		t.Fatalf("authenticated user = %q, want alice", user)
	}
	if pty == nil || pty.Term != "xterm-256color" || pty.Cols != 120 || pty.Rows != 40 {
		t.Fatalf("pty-req = %+v, want xterm-256color 120x40", pty)
	}

	if err := sh.Write([]byte("hello\n")); err != nil {
		t.Fatalf("Write: %v", err)
	}
	waitFor(t, "echo", func() bool { return strings.Contains(out.String(), "hello\n") })
}

func TestShellResizeSendsWindowChange(t *testing.T) {
	srv, kp := setupShellServer(t)
	host, port := srv.addr()
	conn, _ := DialDirect(host, port, 2000)
	sh, err := OpenShell(conn, "alice", kp.PrivateOpenSSH(), "a:22", &recordingVerifier{trust: true}, newBufferSink(), 80, 24)
	if err != nil {
		t.Fatal(err)
	}
	defer sh.Close()
	if err := sh.Resize(100, 30); err != nil {
		t.Fatalf("Resize: %v", err)
	}
	select {
	case <-srv.resized:
	case <-time.After(3 * time.Second):
		t.Fatal("no window-change received")
	}
	srv.mu.Lock()
	defer srv.mu.Unlock()
	if got := srv.resizes[len(srv.resizes)-1]; got != [2]uint32{100, 30} {
		t.Fatalf("window-change = %v, want [100 30]", got)
	}
}

func TestShellCloseNotifiesSinkOnce(t *testing.T) {
	srv, kp := setupShellServer(t)
	host, port := srv.addr()
	conn, _ := DialDirect(host, port, 2000)
	out := newBufferSink()
	sh, err := OpenShell(conn, "alice", kp.PrivateOpenSSH(), "a:22", &recordingVerifier{trust: true}, out, 80, 24)
	if err != nil {
		t.Fatal(err)
	}
	if err := sh.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	select {
	case <-out.closed:
	case <-time.After(3 * time.Second):
		t.Fatal("sink never notified of close")
	}
	_ = sh.Close() // idempotent, must not panic or notify again
	select {
	case r := <-out.closed:
		t.Fatalf("sink notified twice (second reason %q)", r)
	case <-time.After(200 * time.Millisecond):
	}
	if err := sh.Write([]byte("x")); err == nil {
		t.Fatal("expected error writing to closed shell")
	}
}
