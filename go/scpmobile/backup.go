package scpmobile

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"

	"golang.org/x/crypto/argon2"
)

// SCP encrypted settings backup, format v1 (crates/scp-core/src/backup/crypto.rs in the desktop repo):
//
//	"SCPBAK01" | u32 little-endian header length | JSON header | AES-256-GCM ciphertext+tag
//
// The exact header bytes (magic and length included) are the GCM additional data, and the key is
// Argon2id v1.3 over the passphrase and the header's salt.
const (
	backupMagic    = "SCPBAK01"
	maxBackupBytes = 32 * 1024 * 1024
	maxBackupHdr   = 1024
	minBackupBytes = 28 // magic + length + 16-byte tag, as in the desktop reader
	maxPassphrase  = 4096
)

// Messages never include data from the file or the passphrase.
var (
	errBackupInvalid     = errors.New("backup: invalid or unsupported settings backup")
	errBackupHeaderLen   = errors.New("backup: invalid backup header length")
	errBackupHeader      = errors.New("backup: invalid backup header")
	errBackupAlgorithm   = errors.New("backup: unsupported backup version or algorithm")
	errBackupCost        = errors.New("backup: unsupported or excessive backup KDF cost")
	errBackupPassphrase  = errors.New("backup: passphrase must contain 1-4096 bytes")
	errBackupDecryptFail = errors.New("backup: wrong passphrase or damaged backup")
)

// backupHeader mirrors the desktop `Header`. serde writes salt and nonce as JSON number arrays.
type backupHeader struct {
	Version     uint32 `json:"version"`
	KDF         string `json:"kdf"`
	Cipher      string `json:"cipher"`
	MemoryKiB   uint32 `json:"memory_kib"`
	Iterations  uint32 `json:"iterations"`
	Parallelism uint32 `json:"parallelism"`
	Salt        []int  `json:"salt"`
	Nonce       []int  `json:"nonce"`
}

// validateBackupCost bounds the KDF cost. It must run before Argon2: the header is only
// authenticated after the key is derived, so an unchecked file could force a huge allocation,
// and argon2.IDKey panics on zero time or threads.
func validateBackupCost(memoryKiB, iterations, parallelism uint32) error {
	if memoryKiB < 8192 || memoryKiB > 65536 || iterations < 1 || iterations > 3 || parallelism != 1 {
		return errBackupCost
	}
	return nil
}

// fixedBytes converts a JSON number array to exactly n bytes; any other shape is an error.
func fixedBytes(vals []int, n int) ([]byte, bool) {
	if len(vals) != n {
		return nil, false
	}
	out := make([]byte, n)
	for i, v := range vals {
		if v < 0 || v > 255 {
			return nil, false
		}
		out[i] = byte(v)
	}
	return out, true
}

func parseBackupHeader(raw []byte) (*backupHeader, []byte, []byte, error) {
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.DisallowUnknownFields()
	var h backupHeader
	if err := dec.Decode(&h); err != nil {
		return nil, nil, nil, errBackupHeader
	}
	if _, err := dec.Token(); err != io.EOF { // trailing data after the header object
		return nil, nil, nil, errBackupHeader
	}
	salt, ok := fixedBytes(h.Salt, 16)
	if !ok {
		return nil, nil, nil, errBackupHeader
	}
	nonce, ok := fixedBytes(h.Nonce, 12)
	if !ok {
		return nil, nil, nil, errBackupHeader
	}
	return &h, salt, nonce, nil
}

// DecryptBackup decrypts an SCP settings backup and returns its plaintext JSON payload
// (the desktop `Archive`). It fails with one generic error for a wrong passphrase or any damage.
func DecryptBackup(data []byte, passphrase string) ([]byte, error) {
	if len(data) < minBackupBytes || len(data) > maxBackupBytes || string(data[:8]) != backupMagic {
		return nil, errBackupInvalid
	}
	hdrLen := binary.LittleEndian.Uint32(data[8:12])
	if hdrLen > maxBackupHdr || uint64(hdrLen)+minBackupBytes > uint64(len(data)) {
		return nil, errBackupHeaderLen
	}
	end := 12 + int(hdrLen)
	h, salt, nonce, err := parseBackupHeader(data[12:end])
	if err != nil {
		return nil, err
	}
	if h.Version != 1 || h.KDF != "argon2id-19" || h.Cipher != "aes-256-gcm" {
		return nil, errBackupAlgorithm
	}
	if err := validateBackupCost(h.MemoryKiB, h.Iterations, h.Parallelism); err != nil {
		return nil, err
	}
	if len(passphrase) < 1 || len(passphrase) > maxPassphrase {
		return nil, errBackupPassphrase
	}

	key := argon2.IDKey([]byte(passphrase), salt, h.Iterations, h.MemoryKiB, uint8(h.Parallelism), 32)
	defer clear(key)
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, errBackupDecryptFail
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, errBackupDecryptFail
	}
	plain, err := gcm.Open(nil, nonce, data[end:], data[:end])
	if err != nil {
		return nil, errBackupDecryptFail
	}
	return plain, nil
}
