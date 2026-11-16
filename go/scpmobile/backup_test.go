package scpmobile

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"encoding/binary"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"golang.org/x/crypto/argon2"
)

func vector(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("..", "..", "testdata", name))
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func sameJSON(t *testing.T, got, want []byte) {
	t.Helper()
	var g, w any
	if err := json.Unmarshal(got, &g); err != nil {
		t.Fatalf("decrypted payload is not JSON: %v", err)
	}
	if err := json.Unmarshal(want, &w); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(g, w) {
		t.Fatalf("payload mismatch:\n got %s\nwant %s", got, want)
	}
}

// buildBackup writes a v1 backup with a cheap KDF so tests stay fast. header is the JSON header
// object (so tests can corrupt it); the key is derived from the cost fields in it.
func buildBackup(t *testing.T, header map[string]any, payload []byte, pass string) []byte {
	t.Helper()
	raw, err := json.Marshal(header)
	if err != nil {
		t.Fatal(err)
	}
	out := append([]byte("SCPBAK01"), 0, 0, 0, 0)
	binary.LittleEndian.PutUint32(out[8:], uint32(len(raw)))
	out = append(out, raw...)
	toBytes := func(v any) []byte {
		arr, _ := v.([]int)
		b := make([]byte, len(arr))
		for i, x := range arr {
			b[i] = byte(x)
		}
		return b
	}
	salt, nonce := toBytes(header["salt"]), toBytes(header["nonce"])
	key := argon2.IDKey([]byte(pass), salt, uint32(header["iterations"].(int)), uint32(header["memory_kib"].(int)), 1, 32)
	block, _ := aes.NewCipher(key)
	gcm, _ := cipher.NewGCM(block)
	if len(nonce) != 12 {
		nonce = make([]byte, 12)
	}
	return gcm.Seal(out, nonce, payload, out[:12+len(raw)])
}

func cheapHeader() map[string]any {
	seq := func(n, off int) []int {
		s := make([]int, n)
		for i := range s {
			s[i] = i + off
		}
		return s
	}
	return map[string]any{
		"version": 1, "kdf": "argon2id-19", "cipher": "aes-256-gcm",
		"memory_kib": 8192, "iterations": 1, "parallelism": 1,
		"salt": seq(16, 1), "nonce": seq(12, 100),
	}
}

func TestDecryptBackupRealVector(t *testing.T) {
	pass := string(vector(t, "backup-v1.passphrase"))
	got, err := DecryptBackup(vector(t, "backup-v1.scpbak"), pass)
	if err != nil {
		t.Fatal(err)
	}
	sameJSON(t, got, vector(t, "backup-v1.plain.json"))
}

func TestDecryptBackupVectorFailures(t *testing.T) {
	data := vector(t, "backup-v1.scpbak")
	pass := string(vector(t, "backup-v1.passphrase"))
	flip := func(i int) []byte { c := bytes.Clone(data); c[i] ^= 1; return c }

	cases := map[string]struct {
		data []byte
		pass string
	}{
		"wrong passphrase":   {data, pass + "x"},
		"flipped ciphertext": {flip(len(data) - 20), pass},
		"flipped tag":        {flip(len(data) - 1), pass},
		// "version" -> "Version": Go's JSON matching is case-insensitive, so the header still parses
		// identically and only the GCM additional-data check can reject it.
		"header altered, same meaning (AAD)": {func() []byte { c := bytes.Clone(data); c[12+2] ^= 0x20; return c }(), pass},
		"bad magic":                          {flip(0), pass},
		"empty":                              {nil, pass},
		"too short":                          {data[:27], pass},
		"truncated":                          {data[:len(data)-1], pass},
	}
	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			got, err := DecryptBackup(c.data, c.pass)
			if err == nil || got != nil {
				t.Fatalf("expected an error and no plaintext, got %q, %v", got, err)
			}
		})
	}
}

func TestDecryptBackupCheapRoundTrip(t *testing.T) {
	payload := []byte(`{"hello":"wörld"}`)
	got, err := DecryptBackup(buildBackup(t, cheapHeader(), payload, "pw"), "pw")
	if err != nil || !bytes.Equal(got, payload) {
		t.Fatalf("round trip: %q, %v", got, err)
	}
}

func TestDecryptBackupHeaderValidation(t *testing.T) {
	mut := func(f func(h map[string]any)) []byte {
		h := cheapHeader()
		f(h)
		return buildBackup(t, h, []byte(`{}`), "pw")
	}
	cases := map[string][]byte{
		"unknown field":      mut(func(h map[string]any) { h["extra"] = 1 }),
		"wrong version":      mut(func(h map[string]any) { h["version"] = 2 }),
		"wrong kdf":          mut(func(h map[string]any) { h["kdf"] = "argon2i-19" }),
		"wrong cipher":       mut(func(h map[string]any) { h["cipher"] = "chacha20" }),
		"short salt":         mut(func(h map[string]any) { h["salt"] = make([]int, 15) }),
		"long salt":          mut(func(h map[string]any) { h["salt"] = make([]int, 17) }),
		"short nonce":        mut(func(h map[string]any) { h["nonce"] = make([]int, 11) }),
		"salt byte too big":  mut(func(h map[string]any) { s := make([]int, 16); s[3] = 256; h["salt"] = s }),
		"salt byte negative": mut(func(h map[string]any) { s := make([]int, 16); s[3] = -1; h["salt"] = s }),
		"missing field":      mut(func(h map[string]any) { delete(h, "salt") }),
	}
	for name, data := range cases {
		t.Run(name, func(t *testing.T) {
			if got, err := DecryptBackup(data, "pw"); err == nil || got != nil {
				t.Fatalf("expected an error, got %q, %v", got, err)
			}
		})
	}
}

func TestDecryptBackupFraming(t *testing.T) {
	good := buildBackup(t, cheapHeader(), []byte(`{}`), "pw")
	over := bytes.Clone(good)
	binary.LittleEndian.PutUint32(over[8:], 1025) // above the 1024 cap
	huge := bytes.Clone(good)
	binary.LittleEndian.PutUint32(huge[8:], 0xFFFFFFFF)
	beyond := bytes.Clone(good)
	binary.LittleEndian.PutUint32(beyond[8:], uint32(len(good))) // header would run past the file
	for name, data := range map[string][]byte{"over cap": over, "u32 max": huge, "past end": beyond} {
		t.Run(name, func(t *testing.T) {
			if got, err := DecryptBackup(data, "pw"); err == nil || got != nil {
				t.Fatalf("expected an error, got %q, %v", got, err)
			}
		})
	}
	if _, err := DecryptBackup(make([]byte, maxBackupBytes+1), "pw"); err == nil {
		t.Fatal("oversize input must be rejected")
	}
}

func TestBackupCostIsCheckedBeforeKDF(t *testing.T) {
	bad := [][3]uint32{{8191, 1, 1}, {65537, 1, 1}, {1 << 30, 1, 1}, {8192, 0, 1}, {8192, 4, 1}, {8192, 1, 0}, {8192, 1, 2}}
	for _, c := range bad {
		if err := validateBackupCost(c[0], c[1], c[2]); err == nil {
			t.Errorf("cost %v must be rejected", c)
		}
	}
	for _, c := range [][3]uint32{{8192, 1, 1}, {65536, 3, 1}, {32768, 2, 1}} {
		if err := validateBackupCost(c[0], c[1], c[2]); err != nil {
			t.Errorf("cost %v must be accepted: %v", c, err)
		}
	}
	// End to end: a hostile header must fail fast (no multi-GiB allocation, no panic from argon2).
	for name, c := range map[string][3]int{"huge memory": {1 << 22, 1, 1}, "zero iterations": {8192, 0, 1}, "zero parallelism": {8192, 1, 0}} {
		h := cheapHeader()
		h["memory_kib"], h["iterations"], h["parallelism"] = c[0], c[1], c[2]
		raw, _ := json.Marshal(h)
		data := append([]byte("SCPBAK01"), 0, 0, 0, 0)
		binary.LittleEndian.PutUint32(data[8:], uint32(len(raw)))
		data = append(append(data, raw...), make([]byte, 32)...)
		if _, err := DecryptBackup(data, "pw"); err == nil {
			t.Errorf("%s: expected an error", name)
		}
	}
}

func TestDecryptBackupPassphraseBounds(t *testing.T) {
	data := buildBackup(t, cheapHeader(), []byte(`{}`), "pw")
	if _, err := DecryptBackup(data, ""); err == nil {
		t.Error("empty passphrase must be rejected")
	}
	if _, err := DecryptBackup(data, strings.Repeat("a", 4097)); err == nil {
		t.Error("4097-byte passphrase must be rejected")
	}
	max := strings.Repeat("a", 4096)
	ok := buildBackup(t, cheapHeader(), []byte(`{}`), max)
	if _, err := DecryptBackup(ok, max); err != nil {
		t.Errorf("4096-byte passphrase must work: %v", err)
	}
}

func TestDecryptBackupErrorsDoNotLeakSecrets(t *testing.T) {
	_, err := DecryptBackup(buildBackup(t, cheapHeader(), []byte(`{"secret":"hunter2"}`), "right"), "wrong-passphrase")
	if err == nil {
		t.Fatal("expected an error")
	}
	for _, s := range []string{"wrong-passphrase", "hunter2", "right"} {
		if strings.Contains(err.Error(), s) {
			t.Errorf("error %q leaks %q", err, s)
		}
	}
}
