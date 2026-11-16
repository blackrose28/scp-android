package scpmobile

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// fakeController implements just the auth-method authenticate endpoint.
type fakeController struct {
	t        *testing.T
	mu       sync.Mutex
	commands []string
	// pendingPolls is how many "token" polls answer 400 before success.
	pendingPolls int
	failStart    int // if non-zero, start answers with this status
	failPoll     int // if non-zero, token poll answers with this status
	// pendingStatus is the status of a still-pending poll (default 400). With
	// 2xx the body is an empty attributes object, as a real controller sends.
	pendingStatus int
	srv           *httptest.Server
}

func newFakeController(t *testing.T) *fakeController {
	f := &fakeController{t: t}
	f.srv = httptest.NewServer(http.HandlerFunc(f.handle))
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fakeController) handle(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if r.Method != http.MethodPost || r.URL.Path != "/v1/auth-methods/amoidc_test:authenticate" {
		http.NotFound(w, r)
		return
	}
	var body struct {
		Command    string `json:"command"`
		TokenID    string `json:"token_id"`
		Attributes struct {
			TokenID string `json:"token_id"`
		} `json:"attributes"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	f.commands = append(f.commands, body.Command)
	w.Header().Set("Content-Type", "application/json")
	switch body.Command {
	case "start":
		if f.failStart != 0 {
			http.Error(w, `{"kind":"Unauthenticated"}`, f.failStart)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"attributes": map[string]any{"auth_url": "https://idp.example/login?x=1", "token_id": "tok-id-1"},
		})
	case "token":
		if body.TokenID != "tok-id-1" || body.Attributes.TokenID != "tok-id-1" {
			f.t.Errorf("poll must send token_id both top-level and in attributes, got %+v", body)
		}
		if f.failPoll != 0 {
			http.Error(w, `{"kind":"Internal"}`, f.failPoll)
			return
		}
		if f.pendingPolls > 0 {
			f.pendingPolls--
			switch {
			case f.pendingStatus >= 200 && f.pendingStatus < 300:
				w.WriteHeader(f.pendingStatus)
				_, _ = w.Write([]byte(`{"attributes":{}}`))
			case f.pendingStatus != 0:
				http.Error(w, `{"kind":"Pending"}`, f.pendingStatus)
			default:
				http.Error(w, `{"kind":"InvalidArgument"}`, http.StatusBadRequest)
			}
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"attributes": map[string]any{
				"token":           "at_secret_token",
				"expiration_time": "2030-01-02T03:04:05Z",
			},
		})
	default:
		http.Error(w, "bad command", http.StatusBadRequest)
	}
}

func TestStartOidcReturnsAuthURL(t *testing.T) {
	f := newFakeController(t)
	p, err := StartOidc(f.srv.URL+"/", "amoidc_test") // trailing slash tolerated
	if err != nil {
		t.Fatalf("StartOidc: %v", err)
	}
	if got := p.AuthURL(); got != "https://idp.example/login?x=1" {
		t.Fatalf("AuthURL = %q", got)
	}
}

func TestStartOidcValidatesInputs(t *testing.T) {
	for name, tc := range map[string]struct{ url, id string }{
		"empty url":   {"", "amoidc_test"},
		"empty id":    {"https://b.example", " "},
		"bad scheme":  {"ftp://b.example", "amoidc_test"},
		"no host":     {"https://", "amoidc_test"},
		"not a url":   {"::::", "amoidc_test"},
		"id has path": {"https://b.example", "a/b"},
	} {
		if _, err := StartOidc(tc.url, tc.id); err == nil {
			t.Errorf("%s: expected error", name)
		}
	}
}

func TestStartOidcServerError(t *testing.T) {
	f := newFakeController(t)
	f.failStart = http.StatusForbidden
	_, err := StartOidc(f.srv.URL, "amoidc_test")
	if err == nil || !strings.Contains(err.Error(), "403") {
		t.Fatalf("want error mentioning 403, got %v", err)
	}
}

func TestPollPendingThenDone(t *testing.T) {
	f := newFakeController(t)
	f.pendingPolls = 2
	p, err := StartOidc(f.srv.URL, "amoidc_test")
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		r, err := p.Poll()
		if err != nil {
			t.Fatalf("poll %d: %v", i, err)
		}
		if r.Done() || r.Token() != "" {
			t.Fatalf("poll %d should be pending", i)
		}
	}
	r, err := p.Poll()
	if err != nil {
		t.Fatal(err)
	}
	if !r.Done() || r.Token() != "at_secret_token" {
		t.Fatalf("want done with token, got done=%v", r.Done())
	}
	if r.ExpirationUnixMs() != 1893553445000 {
		t.Fatalf("ExpirationUnixMs = %d", r.ExpirationUnixMs())
	}
}

func TestPollTreats2xxWithEmptyTokenAsPending(t *testing.T) {
	for _, status := range []int{http.StatusOK, http.StatusAccepted} {
		f := newFakeController(t)
		f.pendingPolls = 1
		f.pendingStatus = status
		p, err := StartOidc(f.srv.URL, "amoidc_test")
		if err != nil {
			t.Fatal(err)
		}
		r, err := p.Poll()
		if err != nil || r.Done() {
			t.Fatalf("status %d with empty token must be pending, got done=%v err=%v", status, r != nil && r.Done(), err)
		}
		r, err = p.Poll()
		if err != nil || !r.Done() {
			t.Fatalf("status %d: second poll should complete, got err=%v", status, err)
		}
	}
}

func TestPollTreats404AsPending(t *testing.T) {
	f := newFakeController(t)
	f.failPoll = http.StatusNotFound
	p, err := StartOidc(f.srv.URL, "amoidc_test")
	if err != nil {
		t.Fatal(err)
	}
	r, err := p.Poll()
	if err != nil || r.Done() {
		t.Fatalf("404 must mean pending, got done=%v err=%v", r != nil && r.Done(), err)
	}
}

func TestPollUnexpectedStatusIsError(t *testing.T) {
	f := newFakeController(t)
	f.failPoll = http.StatusInternalServerError
	p, err := StartOidc(f.srv.URL, "amoidc_test")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := p.Poll(); err == nil || !strings.Contains(err.Error(), "500") {
		t.Fatalf("want error mentioning 500, got %v", err)
	}
}

func TestOidcZeroValuesReturnErrors(t *testing.T) {
	var p OidcPending
	if _, err := p.Poll(); err == nil {
		t.Error("zero OidcPending.Poll must error, not panic")
	}
	if p.AuthURL() != "" {
		t.Error("zero OidcPending.AuthURL must be empty")
	}
	var nilP *OidcPending
	if _, err := nilP.Poll(); err == nil {
		t.Error("nil OidcPending.Poll must error")
	}
	var r OidcResult
	if r.Done() || r.Token() != "" || r.ExpirationUnixMs() != 0 {
		t.Error("zero OidcResult must be not-done and empty")
	}
}

func TestTokenNotLeakedInErrors(t *testing.T) {
	f := newFakeController(t)
	f.failPoll = http.StatusInternalServerError
	p, _ := StartOidc(f.srv.URL, "amoidc_test")
	_, err := p.Poll()
	if err != nil && strings.Contains(err.Error(), "at_secret_token") {
		t.Fatal("error leaks token")
	}
}
