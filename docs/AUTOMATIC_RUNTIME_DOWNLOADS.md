# Automatic runtime downloads

The launcher performs first-run runtime setup on the first app launch. Android does not
allow an APK to execute its own setup code during the package-install transaction, so
the download starts immediately after the user opens the newly installed app.

## First-run flow

1. Verify the device exposes the `arm64-v8a` ABI.
2. Collect manufacturer, model, SoC, supported ABIs, RAM and Vulkan device information.
3. Query published `runtime-v*` pre-releases (app releases use a different tag prefix).
4. Download the runtime ZIP and its SHA-256 sidecar over HTTPS.
5. Check available storage before download and inspect ZIP central-directory sizes before extraction.
6. Extract into a private staging directory, reject unsafe/duplicate paths and verify entry sizes.
7. Restore executable permissions, validate manifest v2, per-component SHA-256 and binary ABI.
8. Atomically activate the bundle; preserve/restore the previous bundle if activation fails.

If setup fails, the app shows the failing stage and reason. The user can retry with
**Repair / download runtime**. A failed attempt must never replace a previously valid bundle.

## Required release assets

Every compatible release must publish both files:

- `moving-out-2-runtime-arm64-v8a.zip`
- `moving-out-2-runtime-arm64-v8a.zip.sha256`

The checksum file must contain the lowercase SHA-256 digest of the ZIP (the
standard `sha256sum` output format is accepted). This detects archive corruption
relative to the release checksum; it is not a signed publisher attestation. The ZIP must contain
`runtime-manifest.json` at its root and follow
[Runtime Manifest v2](RUNTIME_MANIFEST_V2.md).

## Android ZIP portability

The Android installer does not recreate Unix symbolic links from ZIP external attributes.
Therefore the packager must dereference Wine library symlinks into regular files before
creating the ZIP. The packaging script now rejects unresolved symlinks to prevent a
manifest that validates on Linux but fails after Android extraction.

## Storage and extraction

The runtime archive is large. The app checks available internal storage before download
and checks the declared expanded ZIP size plus a 384 MiB safety reserve before extraction.
If space is insufficient, setup stops before replacing the active bundle and reports the
required and available MiB. Keep additional space for the downloaded archive and the
existing runtime backup while an update is staged.

## Device and graphics driver policy

The runtime payload targets ARM64 Android and contains x86-64 Wine guest components.
The launcher reports the actual Vulkan physical device when the native probe succeeds,
alongside SoC and ABI information. It prefers the Android system Vulkan driver. Turnip
is an optional userspace driver payload and must not be force-loaded solely from a
Qualcomm/Adreno heuristic: compatibility and successful loader enumeration must be
verified on the actual device. The app cannot silently install or replace kernel/system
drivers without privileged platform support.

## Security and distribution

The app selects the newest published, non-draft `runtime-v*` pre-release (the
`/releases/latest` endpoint excludes pre-releases), downloads named assets over HTTPS,
verifies archive SHA-256, rejects traversal and oversized archives, validates all
manifest component hashes and ABI roles, and activates with staging/backup/rollback.
An absent release or missing asset fails closed.

This installer does not fetch or redistribute the game itself. Runtime release assets
must be built from legally distributable upstream components and their licenses/notices
must accompany the release. Do not publish proprietary game files or binaries without
redistribution rights.
