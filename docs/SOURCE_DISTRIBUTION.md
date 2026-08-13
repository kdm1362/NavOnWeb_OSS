# Source distribution

This repository is the preferred form for modifying NavOnWeb. Every distributed APK or Android App Bundle should have a matching immutable public commit or source tag.

## Creating a matching source revision

1. Start from the exact Android, browser, Worker/Pages, build, and test sources used for the binary.
2. Include the Gradle wrapper, version catalog, licenses, notices, and upstream revision lock.
3. Run the Android and web checks in [BUILDING.md](BUILDING.md).
4. Stage the intended source tree and regenerate `SOURCE_MANIFEST.sha256`.
5. Commit and tag that source tree.
6. Build the binary with `-PsourceCodeUrl` set to the immutable `/tree/v0.1.14-p0-source` (or another matching versioned `v...-source` tag) or `/tree/<40-character-commit>` URL.

To regenerate the manifest from the staged Git index on Windows:

```powershell
git add <reviewed-source-paths>
.\tools\update-source-manifest.ps1
git add SOURCE_MANIFEST.sha256
```

Review the intended paths before staging them; do not use a broad staging command that can include unrelated untracked files. `SOURCE_MANIFEST.sha256` lists the SHA-256 digest of each staged source file except the manifest itself. The immutable Git revision remains the authoritative source identifier.

## External inputs

The following deployment inputs are not source code and are not included:

- Android Auto identity certificates and private keys
- Android and Google Play signing keys and passwords
- Cloudflare, Supabase, TURN, and other administrative credentials
- Service signing private keys and server-side data
- Pairing secrets, user logs, diagnostic uploads, and device identifiers
- Machine-specific SDK paths and generated build or deployment output

Public service origins, publishable client keys, certificate fingerprints, and signature-verification public keys may be provided as build configuration when the client requires them.

The public signaling and browser deployment source is included under `cloudflare/`. External service state and credentials remain separate from the client source.
