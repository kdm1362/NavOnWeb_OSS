# NavOnWeb

NavOnWeb is an Android application that presents an Android Auto projection session in a local-network web browser. This repository contains the Android application, the browser client bundled in the APK, and automated tests.

This repository is the public source distribution for NavOnWeb. It intentionally does not contain Android Auto identity certificates or private keys, Android/Play signing keys, deployed service secrets, user diagnostic data, device identifiers, or private development records.

Copyright (C) 2026 NavOnWeb contributors.

## License

NavOnWeb is distributed under the GNU General Public License, version 3 or (at your option) any later version. See [LICENSE](LICENSE).

Some portions are based on or informed by other free and open-source projects. Their licenses, revisions, and the scope of reuse are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [third_party/UPSTREAM.lock](third_party/UPSTREAM.lock). Material changes and provenance boundaries are summarized in [docs/MODIFICATIONS.md](docs/MODIFICATIONS.md).

Android, Android Auto, Google Play, Cloudflare, Supabase, WebRTC, and Tesla are trademarks of their respective owners. This project is not endorsed by those owners.

## Build

See [docs/BUILDING.md](docs/BUILDING.md) for Android build and test instructions. Runtime identity credentials, signing credentials, and independently operated network services are external deployment inputs and are not part of this Android program's Corresponding Source.

## Source correspondence

Debug builds link to `https://github.com/kdm1362/NavOnWeb_OSS` by default. Release tasks require the `sourceCodeUrl` Gradle property to identify the exact matching public commit or immutable tag. See [docs/SOURCE_DISTRIBUTION.md](docs/SOURCE_DISTRIBUTION.md).

`SOURCE_MANIFEST.sha256` records the SHA-256 digest of every release-source candidate except the manifest itself. It is an audit aid; the immutable Git commit or tag remains the authoritative source identifier.
