# folio

A from-scratch Minecraft: Java Edition protocol core. No Paper, no Spigot, no Netty — just
the JDK, native NIO, and a small allocation-reducing pooling library.

`folio` is the codename. It is a short English word meaning a single sheet / leaf of paper,
chosen to sit next to "Paper" without being a fork of it. The repository name is English only.

## What this is

`folio` implements the 26.x wire protocol directly: VarInt framing, packet IDs, the
Handshaking / Status / Login / Configuration / Play state machine, encryption, session
verification, and keep-alives. It is an experimental protocol core, not a playable server.

The first milestone is **minimal joinable connectivity**: a protocol-level client walks the
whole state machine and then stays connected. There is no world, no chunks, no entities, no
commands, and no plugin API.

## Supported versions

| Game version | Protocol | Data version |
| --- | --- | --- |
| 26.1 (and 26.1.1 / 26.1.2 hotfixes) | 775 | 4786 |
| 26.2 | 776 | 4903 |
| 26.3 | 777 | 5023 |

26.1 is the minimum. A client below 775 is disconnected with an explicit bilingual message.
26.4 will be aligned in a separate change when it ships; the version layer has a registration
point for it.

The three drops share one packet table with a per-version difference map. Some Play-state IDs
for 775 and 776 are marked `UNVERIFIED` in `docs/DESIGN.md`; the code never invents a number
for them and falls back to the registered baseline behind an explicit flag.

## Requirements

- Java 25 or newer (26.1 itself requires Java 25)
- Maven 3.9+ to build

Runtime dependency: `net.de5.rainbowcrrsys:gc-primitives:0.2.0` from Maven Central.
Tests use JUnit 5.10.2. There are no other third-party dependencies.

## Build and run

```sh
mvn -q -B package
java -jar target/folio-0.1.0-SNAPSHOT.jar
```

The server listens on `25565` by default with the MOTD `A 26.x protocol core`. Edit
`config.yaml` to change the port, MOTD, `online-mode`, session URL, frame limits,
keep-alive timings, selector count, and pool settings.

Configuration is a small YAML subset parsed by gc-primitives 0.2.0. `GC_PRIMITIVES_CONFIG`
overrides the config path. Pool settings and server settings are hot-reloaded; a changed
`online-mode` applies to **new connections only**, existing sessions keep the mode they
started with.

## Authentication

`online-mode` defaults to `true`. Both paths are fully implemented:

- **Online**: a 1024-bit RSA keypair is generated in memory at startup. Encryption Request
  (`hello`), AES/CFB8 stream encryption, and a `hasJoined` GET against the configured
  session URL are all performed. In CI the URL points at a local mock session server.
- **Offline**: Login Start goes straight to Login Success with a UUIDv3 derived from
  `OfflinePlayer:<name>`.

## Observability

Every 30 seconds the 16-field unified metrics snapshot is written to `logs/metrics.json`.
Logs go through `java.lang.System.Logger`, so the runtime stays dependency-free.
Set `GC_PRIMITIVES_DIAG=1` to enable `memory_scope` diagnostics.

## Continuous integration

GitHub Actions runs `mvn -B verify` on every push, pull request, and manual dispatch, over a
matrix of three protocol versions and two JDKs (25 and 26). The integration test drives a
scripted client through all five states and holds keep-alives. No credentials are stored in
the repository or in CI configuration; the workflow uses the automatically provided token.

## Limitations

- A real 26.x client needs more Configuration-phase data (registry data, known packs, tags)
  before it finishes loading. This milestone verifies the state machine and connection
  survival with a protocol-level client, not a vanilla client joining a world.
- Exact 775/776 Play packet IDs that could not be confirmed are flagged `UNVERIFIED`; see
  `docs/DESIGN.md`. They are not guessed.
- One selectable selector thread owns its own pooled objects end to end. Multiple selectors
  scale by connection hash, with one pool set per selector; leases never cross threads.

## License

Apache License 2.0. See [LICENSE](LICENSE).
