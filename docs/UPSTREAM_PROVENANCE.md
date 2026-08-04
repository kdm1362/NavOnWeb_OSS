# Upstream provenance

NavOnWeb's Android Auto protocol and projection implementation was developed with reference to OpenAuto and AASDK. Exact revision identifiers and selected-file hashes are recorded in `third_party/UPSTREAM.lock`.

The selected OpenAuto files under `third_party/openauto-reference/` are included for attribution and source correspondence and remain subject to GPL-3.0-or-later.

`andreknieriem/open-headunit` at commit `738b07ff7e765d9b570d27dee6d15901ad30b80c` was reviewed as an architecture and interoperability reference. A commit-scoped static comparison identified no open-headunit program source, generated fixture, UI asset, build script, or credential asset incorporated into this curated public source tree or the Android package inputs under `app/src/`. Its upstream license is AGPL-3.0-or-later and is identified accurately to avoid conflating it with NavOnWeb's GPL-3.0-or-later license.

Upstream repositories:

- https://github.com/f1xpl/openauto
- https://github.com/f1xpl/aasdk
- https://github.com/andreknieriem/open-headunit
