package scpmobile

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

// Integration test against a real `boundary dev` (needs the boundary binary and
// Docker for its Postgres). Opt in with SCP_BOUNDARY_DEV=1; it uses boundary
// dev's default ports 9200-9202, so it fails fast if they are taken.
//
//	SCP_BOUNDARY_DEV=1 go test -race -run BoundaryDev -v ./scpmobile/
const (
	devAPI      = "http://127.0.0.1:9200"
	devTargetID = "ttcp_1234567890"
	devAuthID   = "ampw_1234567890"
)

func startBoundaryDev(t *testing.T, targetPort int) {
	t.Helper()
	if os.Getenv("SCP_BOUNDARY_DEV") != "1" {
		t.Skip("set SCP_BOUNDARY_DEV=1 to run against `boundary dev` (needs Docker)")
	}
	bin, err := exec.LookPath("boundary")
	if err != nil {
		t.Skip("boundary binary not on PATH")
	}
	if resp, err := http.Get(devAPI + "/v1/scopes"); err == nil {
		resp.Body.Close()
		t.Fatalf("something already listens on %s; stop it first", devAPI)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cmd := exec.CommandContext(ctx, bin, "dev",
		"-host-address=127.0.0.1",
		fmt.Sprintf("-target-default-port=%d", targetPort))
	var logs bytes.Buffer
	cmd.Stdout, cmd.Stderr = &logs, &logs
	// SIGINT (not the default SIGKILL) lets boundary stop its plugins and remove
	// its Postgres container; WaitDelay force-kills it if it hangs.
	cmd.Cancel = func() error { return cmd.Process.Signal(os.Interrupt) }
	cmd.WaitDelay = 30 * time.Second
	if err := cmd.Start(); err != nil {
		cancel()
		t.Fatalf("start boundary dev: %v", err)
	}
	t.Cleanup(func() {
		cancel()
		cmd.Wait()
		if t.Failed() {
			t.Logf("boundary dev output:\n%s", logs.String())
		}
	})
	deadline := time.Now().Add(120 * time.Second)
	for time.Now().Before(deadline) {
		if resp, err := http.Get(devAPI + "/v1/scopes"); err == nil {
			resp.Body.Close()
			// the worker needs a moment to register with the controller
			time.Sleep(5 * time.Second)
			return
		}
		time.Sleep(500 * time.Millisecond)
	}
	t.Fatalf("boundary dev did not become ready; output:\n%s", logs.String())
}

func devLogin(t *testing.T) string {
	t.Helper()
	body, _ := json.Marshal(map[string]any{
		"attributes": map[string]string{"login_name": "admin", "password": "password"},
	})
	resp, err := http.Post(devAPI+"/v1/auth-methods/"+devAuthID+":authenticate",
		"application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out authenticateResponse
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil || out.Attributes.Token == "" {
		t.Fatalf("password login failed: status %d err %v", resp.StatusCode, err)
	}
	return out.Attributes.Token
}

// devHasActiveSession reports whether the admin token can see any active session.
func devHasActiveSession(t *testing.T, token string) bool {
	t.Helper()
	req, _ := http.NewRequest(http.MethodGet, devAPI+"/v1/sessions?scope_id=global&recursive=true", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out struct {
		Items []struct {
			Status string `json:"status"`
		} `json:"items"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		t.Fatalf("list sessions: status %d: %v", resp.StatusCode, err)
	}
	for _, it := range out.Items {
		if it.Status == "active" {
			return true
		}
	}
	return false
}

func TestBoundaryDev(t *testing.T) {
	srv, kp := setupShellServer(t)
	_, port := srv.addr()
	startBoundaryDev(t, port) // one instance shared by the subtests

	t.Run("DialBoundaryToShell", func(t *testing.T) {
		token := devLogin(t)
		sess, err := DialBoundary(devAPI, token, devTargetID)
		if err != nil {
			t.Fatalf("DialBoundary: %v", err)
		}
		defer sess.Close()
		if exp := sess.ExpirationUnixMs(); exp <= time.Now().UnixMilli() {
			t.Fatalf("ExpirationUnixMs = %d, want a future time", exp)
		}
		conn, err := sess.Conn()
		if err != nil {
			t.Fatal(err)
		}

		v := &recordingVerifier{trust: true}
		out := newBufferSink()
		sh, err := OpenShell(conn, "alice", kp.PrivateOpenSSH(), "real-host.internal:22", v, out, 80, 24)
		if err != nil {
			t.Fatalf("OpenShell over Boundary: %v", err)
		}
		if v.alias != "real-host.internal:22" {
			t.Fatalf("verifier alias = %q, want the real host alias", v.alias)
		}
		if err := sh.Write([]byte("through-boundary\n")); err != nil {
			t.Fatal(err)
		}
		waitFor(t, "echo through Boundary", func() bool {
			return strings.Contains(out.String(), "through-boundary\n")
		})
		sh.Close()
		// Closing only the shell must still end the Boundary session.
		waitFor(t, "session no longer active after Shell.Close", func() bool { return !devHasActiveSession(t, token) })
		if err := sess.Close(); err != nil {
			t.Fatalf("session Close: %v", err)
		}
		if err := sess.Close(); err != nil {
			t.Fatalf("second Close must be a no-op: %v", err)
		}
	})

	t.Run("RejectsBadToken", func(t *testing.T) {
		_, err := DialBoundary(devAPI, "at_not_a_real_token", devTargetID)
		if err == nil {
			t.Fatal("expected authorize failure")
		}
		if strings.Contains(err.Error(), "at_not_a_real_token") {
			t.Fatalf("error leaks token: %v", err)
		}
	})

	t.Run("StartOidcOnPasswordMethodFails", func(t *testing.T) {
		// dev's auth method is password, not OIDC: StartOidc must surface the error.
		if _, err := StartOidc(devAPI, devAuthID); err == nil {
			t.Fatal("StartOidc against a password auth method must fail")
		}
	})
}
