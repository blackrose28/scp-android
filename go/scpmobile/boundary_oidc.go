package scpmobile

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"strings"
	"time"
)

const (
	oidcHTTPTimeout  = 30 * time.Second
	maxOidcRespBytes = 1 << 20
)

// OidcPending is an in-progress Boundary OIDC login started by StartOidc.
// Kotlin opens AuthURL in the browser (or hidden WebView) and calls Poll until
// the result is done.
type OidcPending struct {
	endpoint string
	tokenID  string
	authURL  string
	client   *http.Client
}

// OidcResult is one Poll outcome. While the user has not finished logging in
// at the IdP, Done is false and Token is empty.
type OidcResult struct {
	done         bool
	token        string
	expirationMs int64
}

// Done reports whether the login completed and Token is valid.
func (r *OidcResult) Done() bool { return r != nil && r.done }

// Token returns the Boundary auth token, or "" while pending.
func (r *OidcResult) Token() string {
	if r == nil {
		return ""
	}
	return r.token
}

// ExpirationUnixMs returns the token expiration in Unix milliseconds, or 0 if
// unknown or pending.
func (r *OidcResult) ExpirationUnixMs() int64 {
	if r == nil {
		return 0
	}
	return r.expirationMs
}

// AuthURL returns the IdP login URL to open.
func (p *OidcPending) AuthURL() string {
	if p == nil {
		return ""
	}
	return p.authURL
}

type authenticateResponse struct {
	Attributes struct {
		AuthURL        string `json:"auth_url"`
		TokenID        string `json:"token_id"`
		Token          string `json:"token"`
		ExpirationTime string `json:"expiration_time"`
	} `json:"attributes"`
}

// normalizeControllerURL validates a Boundary controller address and returns
// it without a trailing slash.
func normalizeControllerURL(raw string) (string, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return "", errors.New("boundary: controller URL is empty")
	}
	u, err := url.Parse(raw)
	if err != nil {
		return "", fmt.Errorf("boundary: invalid controller URL: %w", err)
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return "", fmt.Errorf("boundary: controller URL scheme must be http or https, got %q", u.Scheme)
	}
	if u.Host == "" {
		return "", errors.New("boundary: controller URL has no host")
	}
	return strings.TrimRight(u.String(), "/"), nil
}

// StartOidc begins an OIDC login against the given auth method
// (amoidc_…) and returns the pending login holding the IdP URL.
func StartOidc(controllerURL, authMethodID string) (*OidcPending, error) {
	base, err := normalizeControllerURL(controllerURL)
	if err != nil {
		return nil, err
	}
	id := strings.TrimSpace(authMethodID)
	if id == "" {
		return nil, errors.New("boundary: auth method ID is empty")
	}
	if strings.ContainsAny(id, "/?#:\\ ") {
		return nil, fmt.Errorf("boundary: invalid auth method ID %q", id)
	}
	jar, err := cookiejar.New(nil)
	if err != nil {
		return nil, fmt.Errorf("boundary: cookie jar: %w", err)
	}
	p := &OidcPending{
		endpoint: base + "/v1/auth-methods/" + id + ":authenticate",
		client:   &http.Client{Timeout: oidcHTTPTimeout, Jar: jar},
	}
	resp, status, err := p.authenticate(map[string]any{"command": "start"})
	if err != nil {
		return nil, fmt.Errorf("boundary: start oidc: %w", err)
	}
	if resp == nil {
		return nil, fmt.Errorf("boundary: start oidc: HTTP %d", status)
	}
	if resp.Attributes.AuthURL == "" || resp.Attributes.TokenID == "" {
		return nil, errors.New("boundary: start oidc: response did not include auth_url and token_id")
	}
	p.authURL = resp.Attributes.AuthURL
	p.tokenID = resp.Attributes.TokenID
	return p, nil
}

// Poll checks whether the user finished logging in. It returns a not-done
// result while the IdP callback has not happened yet, and an error for
// anything unexpected.
func (p *OidcPending) Poll() (*OidcResult, error) {
	if p == nil || p.client == nil || p.tokenID == "" {
		return nil, errUninitialized
	}
	resp, status, err := p.authenticate(map[string]any{
		"command":    "token",
		"token_id":   p.tokenID,
		"attributes": map[string]any{"token_id": p.tokenID},
	})
	if err != nil {
		return nil, fmt.Errorf("boundary: poll oidc: %w", err)
	}
	if resp == nil { // non-2xx
		if status == http.StatusBadRequest || status == http.StatusNotFound {
			return &OidcResult{}, nil // IdP callback not completed yet
		}
		return nil, fmt.Errorf("boundary: poll oidc: HTTP %d", status)
	}
	if strings.TrimSpace(resp.Attributes.Token) == "" {
		return &OidcResult{}, nil // 2xx (e.g. 202) without a token: still pending
	}
	r := &OidcResult{done: true, token: strings.TrimSpace(resp.Attributes.Token)}
	if ts := strings.TrimSpace(resp.Attributes.ExpirationTime); ts != "" {
		if t, perr := time.Parse(time.RFC3339, ts); perr == nil {
			r.expirationMs = t.UnixMilli()
		}
	}
	return r, nil
}

// authenticate posts a command. It returns the decoded body for any 2xx status
// and a nil body for other statuses, which the caller interprets. Response
// bodies are never echoed into errors.
func (p *OidcPending) authenticate(body map[string]any) (*authenticateResponse, int, error) {
	payload, err := json.Marshal(body)
	if err != nil {
		return nil, 0, err
	}
	req, err := http.NewRequest(http.MethodPost, p.endpoint, bytes.NewReader(payload))
	if err != nil {
		return nil, 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	res, err := p.client.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer res.Body.Close()
	data, err := io.ReadAll(io.LimitReader(res.Body, maxOidcRespBytes))
	if err != nil {
		return nil, res.StatusCode, err
	}
	if res.StatusCode < 200 || res.StatusCode > 299 {
		return nil, res.StatusCode, nil
	}
	var out authenticateResponse
	if err := json.Unmarshal(data, &out); err != nil {
		return nil, res.StatusCode, fmt.Errorf("decode response: %w", err)
	}
	return &out, res.StatusCode, nil
}
