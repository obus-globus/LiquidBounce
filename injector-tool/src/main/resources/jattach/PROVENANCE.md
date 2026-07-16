# Vendored `jattach` binaries — provenance

These four native binaries let a plain JRE (one whose runtime image omits the `jdk.attach` module) attach the
injector agent to a running Minecraft JVM. They are executed with the target JVM owner's privileges, so their
provenance matters.

- **Upstream:** [`apangin/jattach`](https://github.com/apangin/jattach) — a tiny native tool to attach to a running
  JVM and load an agent / run a jcmd.
- **License:** Apache License 2.0 (see [`../../../THIRD_PARTY_NOTICES.md`](../../../THIRD_PARTY_NOTICES.md)).
- **Version:** `jattach 2.2` (the linux-x64 build self-reports `jattach 2.2 built on Jan 10 2024`), the official
  `v2.2` release binaries. Statically linked, stripped.

## Pinned SHA-256

The tool verifies the extracted binary against these digests before executing it (`Jattach.verifySha256`), so a
tampered jar resource or a swap of the extracted temp file is refused. To re-verify by hand:

```
sha256sum -c CHECKSUMS.sha256
```

| file | sha256 |
| --- | --- |
| `jattach-linux-x64` | `a08cb795a1e8d11ea6c2dd6adf8c9edead9a7c3bbca07681dad79cc3eaec0ef4` |
| `jattach-linux-arm64` | `a2b015914a1e7db4387884d68e5970e2473aea26e3309153644ce2d66a046293` |
| `jattach-macos` | `e0a397ab291954d4aa1b980a3ffa78a6792c59bb1bd3b7ed43a55966801bfdd9` |
| `jattach-windows-x64.exe` | `0a2358700b1294fdd7f3db23b28e4d9e5577025d694c3abf851bed9d73c9c1b1` |

If you refresh these binaries, update the digests here, in `CHECKSUMS.sha256`, and in `Jattach.SHA256`.
