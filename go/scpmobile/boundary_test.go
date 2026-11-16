package scpmobile

import (
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestSingleConnListenerHandsOutOneConnThenBlocksUntilClosed(t *testing.T) {
	client, ln := newSingleConnListener()

	server, err := ln.Accept()
	if err != nil {
		t.Fatalf("first Accept: %v", err)
	}
	go server.Write([]byte("hello")) // the accepted conn stays open
	got := make([]byte, 5)
	if _, err := io.ReadFull(client, got); err != nil || string(got) != "hello" {
		t.Fatalf("client read = %q, %v", got, err)
	}

	second := make(chan error, 1)
	go func() { _, err := ln.Accept(); second <- err }()
	select {
	case err := <-second:
		t.Fatalf("second Accept must block until Close, returned %v", err)
	case <-time.After(50 * time.Millisecond):
	}
	if err := ln.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	select {
	case err := <-second:
		if !errors.Is(err, net.ErrClosed) {
			t.Fatalf("Accept after Close = %v, want net.ErrClosed", err)
		}
	case <-time.After(time.Second):
		t.Fatal("Accept did not unblock after Close")
	}
	if err := ln.Close(); err != nil {
		t.Fatalf("Close must be idempotent: %v", err)
	}
}

func TestSingleConnListenerCloseBeforeAcceptClosesClientConn(t *testing.T) {
	client, ln := newSingleConnListener()
	ln.Close()
	if _, err := ln.Accept(); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("Accept after Close = %v, want net.ErrClosed", err)
	}
	client.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := client.Read(make([]byte, 1)); err == nil {
		t.Fatal("client read should fail once the listener is closed unaccepted")
	}
}

func TestSingleConnListenerClosesWhenAcceptedConnCloses(t *testing.T) {
	_, ln := newSingleConnListener()
	server, err := ln.Accept()
	if err != nil {
		t.Fatal(err)
	}
	next := make(chan error, 1)
	go func() { _, err := ln.Accept(); next <- err }()
	if err := server.Close(); err != nil {
		t.Fatalf("closing accepted conn: %v", err)
	}
	if err := server.Close(); err != nil && !errors.Is(err, net.ErrClosed) && !errors.Is(err, io.ErrClosedPipe) {
		t.Fatalf("second conn Close: %v", err)
	}
	select {
	case err := <-next:
		if !errors.Is(err, net.ErrClosed) {
			t.Fatalf("Accept = %v, want net.ErrClosed once the one conn is done", err)
		}
	case <-time.After(time.Second):
		t.Fatal("listener must close when its single accepted conn closes, so the proxy ends and tears the session down")
	}
}

func TestSingleConnListenerCallsOnConnClosedOnce(t *testing.T) {
	_, ln := newSingleConnListener()
	calls := 0
	ln.onConnClosed = func() { calls++ }
	server, err := ln.Accept()
	if err != nil {
		t.Fatal(err)
	}
	server.Close()
	server.Close()
	if calls != 1 {
		t.Fatalf("onConnClosed called %d times, want exactly 1", calls)
	}
}

func TestDialBoundaryValidatesInputs(t *testing.T) {
	for name, tc := range map[string]struct{ url, token, target string }{
		"empty url":    {"", "at_x", "ttcp_1"},
		"bad scheme":   {"ftp://b", "at_x", "ttcp_1"},
		"empty token":  {"https://b.example", " ", "ttcp_1"},
		"empty target": {"https://b.example", "at_x", ""},
	} {
		if _, err := DialBoundary(tc.url, tc.token, tc.target); err == nil {
			t.Errorf("%s: expected error", name)
		}
	}
}

func TestDialBoundaryAuthorizeFailureIsReportedWithoutLeakingToken(t *testing.T) {
	var sawAuth string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sawAuth = r.Header.Get("Authorization")
		if r.URL.Path != "/v1/targets/ttcp_missing:authorize-session" || r.Method != http.MethodPost {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusForbidden)
		json.NewEncoder(w).Encode(map[string]any{"kind": "PermissionDenied", "message": "nope"})
	}))
	defer srv.Close()

	_, err := DialBoundary(srv.URL, "at_secret_token", "ttcp_missing")
	if err == nil {
		t.Fatal("expected error")
	}
	if !strings.Contains(err.Error(), "authorize") {
		t.Fatalf("error should say authorize failed: %v", err)
	}
	if strings.Contains(err.Error(), "at_secret_token") {
		t.Fatalf("error leaks token: %v", err)
	}
	if sawAuth != "Bearer at_secret_token" {
		t.Fatalf("controller saw Authorization %q", sawAuth)
	}
}

func TestBoundarySessionZeroValueReturnsErrors(t *testing.T) {
	var s BoundarySession
	if _, err := s.Conn(); err == nil {
		t.Error("zero BoundarySession.Conn must error")
	}
	if err := s.Close(); err == nil {
		t.Error("zero BoundarySession.Close must error")
	}
	if s.ExpirationUnixMs() != 0 || s.ConnectionsLeft() != 0 {
		t.Error("zero BoundarySession accessors must return 0")
	}
	var nilS *BoundarySession
	if _, err := nilS.Conn(); err == nil {
		t.Error("nil BoundarySession.Conn must error")
	}
}
