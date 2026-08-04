# Source distribution

This public repository is maintained as the preferred form for modifying NavOnWeb. For every distributed APK or Android App Bundle, publish a commit or immutable tag containing the complete matching source and build files.

## Release correspondence checklist

1. Freeze the exact source revision used for the binary.
2. Run the Android tests described in `BUILDING.md`.
3. Confirm that all modified Android, bundled-browser, build, and test sources are present.
4. Confirm that Gradle wrapper files, the version catalog, licenses, notices, and upstream revision lock are present.
5. Confirm that credentials, signing keys, service secrets, diagnostic data, device identifiers, private-network records, build outputs, and internal planning records are absent from the full Git history.
6. Build the distributed binary with `-PsourceCodeUrl=https://github.com/kdm1362/NavOnWeb_OSS` or an immutable tag URL.
7. Tag the matching public commit and retain it for as long as required by the GPL and the distribution channel.

`SOURCE_MANIFEST.sha256` can be used to verify a checked-out source tree before the Git commit is created. Regenerate it whenever any tracked source candidate changes.

## Deliberately external inputs

The following are not source code and must never be committed:

- Android Auto identity certificates and private keys
- Android or Google Play signing keys and passwords
- Cloudflare, Supabase, TURN, or other service secrets
- Browser/device pairing secrets and cookies
- User diagnostic reports and logs
- Machine-specific `local.properties` and deployment configuration

Public certificate fingerprints and public service origins may be supplied as build parameters when required, but they are not secrets and should still be reviewed for release correspondence.

Cloudflare-hosted signaling code and Supabase backend code are independently deployed network services, are not linked into the Android binary, and are not included in this Android Corresponding Source repository. The client-side protocol implementation and the browser code bundled in the APK remain included under `app/src/`.
