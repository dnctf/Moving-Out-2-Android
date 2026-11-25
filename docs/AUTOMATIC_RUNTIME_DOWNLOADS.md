# Automatic runtime downloads

The launcher can install the runtime from the latest GitHub Release of
`dnctf/Moving-Out-2-Android`. Runtime payloads are not committed to the source
repository and must be published as release assets.

## Required release assets

Every compatible release must publish both files:

- `moving-out-2-runtime-arm64-v8a.zip`
- `moving-out-2-runtime-arm64-v8a.zip.sha256`

The checksum file must contain the lowercase SHA-256 digest of the ZIP (the
standard `sha256sum` output format is accepted). This detects archive corruption
relative to the release checksum; it is not a signed publisher attestation. The ZIP must contain
`runtime-manifest.json` at its root and follow
[Runtime Manifest v2](RUNTIME_MANIFEST_V2.md).

## Installation safety

The app resolves the latest release using GitHub's public Releases API, downloads
the named assets over HTTPS, verifies the archive SHA-256, rejects traversal and
oversized archives, validates all manifest component hashes and ABI roles, and
activates the bundle with staging/backup/rollback. An absent release or missing
asset fails closed; the existing installed runtime is preserved.

This installer does not fetch or redistribute the game itself. Runtime release
assets must be built from legally distributable upstream components and their
licenses/notices must accompany the release. Do not publish proprietary game
files or binaries without redistribution rights.
