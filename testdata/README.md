# Test vectors

Produced entirely by `scp-core` from the sibling `../SuperControlPanel` repo: `ServersFile` and
`AppConfigForDisk` serialize the settings files, `backup::snapshot_with_version` builds the Archive (with an
in-memory credential store), and `backup::crypto::encrypt` seals it
(format v1: `SCPBAK01` + header + Argon2id-19 + AES-256-GCM). Nothing in the payload is hand-written.

| File | Content |
| --- | --- |
| `backup-v1.scpbak` | encrypted backup (one ssh server, one boundary server, OIDC config, OIDC/OpenVPN/SSH credentials) |
| `backup-v1.plain.json` | the exact plaintext payload that was encrypted |
| `backup-v1.passphrase` | passphrase, UTF-8 with non-ASCII characters, no trailing newline |

Regenerate (salt and nonce are random, so the ciphertext changes; the plaintext does not):

```sh
cp ../SuperControlPanel/Cargo.lock testdata/vecgen/   # pin the sibling's dependency versions
cargo run --manifest-path testdata/vecgen/Cargo.toml -- testdata
rm testdata/vecgen/Cargo.lock; rm -rf testdata/vecgen/target
```

The passphrase and secrets in these files are fake and for tests only.
