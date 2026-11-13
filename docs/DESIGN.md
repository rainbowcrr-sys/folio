# folio — design (first milestone)

Status: design for the first two commits. All repository content is English; the only
non-ASCII user-facing string in the product is the deliberately bilingual version-gate
disconnect message specified for this milestone.

## 1. Scope

Goal: a from-scratch protocol-level core for Minecraft Java Edition 26.x that a scripted
client can drive through every connection state and keep alive. Native NIO, no Netty, no
Paper/Spigot code, no world simulation.

In scope:

- VarInt framing, packet IDs, five connection states
- 26.1 / 26.2 / 26.3 through one table plus a difference map
- Status response (MOTD, version, player counts), Ping/Pong
- Offline and online authentication, both complete
- Encryption (RSA + AES/CFB8) and `hasJoined` session verification
- Configuration state and Play entry
- Keep-Alive (15 s send / 30 s timeout)
- Full gc-primitives 0.2.0 integration
- GitHub Actions matrix CI with protocol-level integration tests

Out of scope for this milestone: chunks, terrain, entities, inventory, chat, commands,
redstone, plugin API, resource packs, registry codecs, real-client world join, and 26.4.

Acceptance is a protocol-level integration test on GitHub Actions (free tier), not a manual
real-client join. This is stated explicitly because a vanilla client expects more
Configuration-phase data than this milestone sends.

## 2. Version layer

| Game | Protocol | Data | Resource pack | Java |
| --- | --- | --- | --- | --- |
| 26.1 | 775 | 4786 | 84.0 | 25 |
| 26.2 | 776 | 4903 | 88.0 | 25 |
| 26.3 | 777 | 5023 | 97.1 | 25 |

Protocol numbers are exact-match values carried in the Handshake. 26.1.1 and 26.1.2 keep
775. A protocol below 775 is rejected before Login with a bilingual disconnect.

`ProtocolVersion` is an enum with a registration point. Adding 26.4 means adding one enum
constant and one difference-map entry; the state machine and codec do not change. This is
the agreed follow-up policy: 26.4 is handled in a new round, not pre-generated here.

### 2.1 Packet table and difference map

The baseline is the 26.3 / protocol 777 table from the official protocol page. Only the
packets this milestone actually emits or consumes are registered; the other ~250 packets
are deliberately not hardcoded.

Baseline (777), clientbound / serverbound:

| State | Direction | Name | ID |
| --- | --- | --- | --- |
| Handshaking | S | `intention` | 0x00 |
| Status | C | `status_response` | 0x00 |
| Status | C | `pong_response` | 0x01 |
| Status | S | `status_request` | 0x00 |
| Status | S | `ping_request` | 0x01 |
| Login | C | `login_disconnect` | 0x00 |
| Login | C | `hello` (Encryption Request) | 0x01 |
| Login | C | `login_finished` | 0x02 |
| Login | C | `login_compression` | 0x03 |
| Login | S | `hello` (Login Start) | 0x00 |
| Login | S | `key` (Encryption Response) | 0x01 |
| Login | S | `login_acknowledged` | 0x03 |
| Configuration | C | `disconnect` | 0x02 |
| Configuration | C | `finish_configuration` | 0x03 |
| Configuration | C | `keep_alive` | 0x04 |
| Configuration | S | `client_information` | 0x00 |
| Configuration | S | `finish_configuration` | 0x03 |
| Configuration | S | `keep_alive` | 0x04 |
| Play | C | `keep_alive` | 0x2D (45) |
| Play | C | `login` | 0x32 (50) |
| Play | C | `disconnect` | 0x20 (32) |
| Play | S | `keep_alive` | 0x1C (28) |

Difference handling: `PacketIds` holds one baseline map and one override map per version.
An override entry may be `UNVERIFIED` (-1). When a lookup hits `UNVERIFIED`, the lookup
returns the baseline ID and records the key in a diagnostic set rather than silently
pretending the number is confirmed. The design document marks those cells; the code never
invents a value.

Confirmed difference signal for 775: protocol 775 added roughly 15 new serverbound Play
packets and shifted later IDs (for example `custom_payload` 0x0A -> 0x16,
`chat_session_update` 0x06 -> 0x0A, `keep_alive` 0x10 -> 0x1C in one published mapping),
and its clientbound Play table is shorter than 777's. Because the exact 775 and 776 Play
IDs are not confirmed from the official page for this milestone, all Play IDs for 775 and
776 are marked `UNVERIFIED` in the difference map. The Handshaking / Status / Login /
Configuration entries for all three versions are registered as shared baseline values.

Consequence: the CI matrix stays green because the scripted client and the server both read
the same registered table, and the unverified cells are visible in `logs/metrics.json` and
the test report. It does **not** claim real 26.1/26.2 client compatibility until those
cells are filled from the official page.

## 3. State machine

```
TCP accept
  -> HANDSHAKING: intention(protocol, address, port, intent)
       intent 1 -> STATUS
       intent 2 -> LOGIN
       protocol < 775 -> bilingual disconnect, close
  STATUS:  status_request -> status_response
           ping_request   -> pong_response
  LOGIN:   hello (Login Start)
           offline -> login_finished
           online  -> hello (Encryption Request) -> key (Encryption Response)
                      -> hasJoined -> login_finished (encrypted)
           -> login_acknowledged
  CONFIGURATION: client_information (ignored), keep_alive responses
                 -> finish_configuration (C) -> finish_configuration (S)
  PLAY:    login (play) is the agreed "login packet" boundary, then Keep-Alive only
```

No `Set Compression` packet is sent, so the uncompressed frame format applies throughout.
No registry data, known packs, tags, or feature flags are sent in this milestone; the
"login packet + KeepAlive" Play boundary was chosen explicitly.

After Login Success in online mode, AES/CFB8 encryption is enabled for both directions,
including the length prefix and packet ID, using the shared secret as both key and IV.

## 4. Authentication

`AuthProvider` has two implementations selected per connection at handshake time.

Offline: UUIDv3 of `OfflinePlayer:<name>`, no encryption (matching vanilla behaviour since
1.21), Login Start is answered directly with Login Success.

Online, per the official protocol page:

1. Startup generates a 1024-bit RSA keypair in memory; it is never written to disk.
2. `hello` (Encryption Request) carries Server ID (empty string), Public Key (X.509 bytes),
   Verify Token (random), and `should authenticate = true`.
3. Client replies with `key`: Shared Secret and Verify Token, both RSA-encrypted.
4. Server decrypts both with its private key and compares the token.
5. Login hash: SHA-1 over the server id string, the shared secret, and the encoded public
   key, formatted with Minecraft's signed-hex digest convention.
6. `GET <session-url>?username=<name>&serverId=<hash>` (optional `ip`). A 204 or a profile
   JSON response is accepted; anything else disconnects.
7. AES/CFB8 is enabled, then Login Success is sent encrypted.

The session URL is configurable so CI can point at `MockSessionServer` (a loopback
`com.sun.net.httpserver` handler). No real Mojang service and no secrets are used in CI.

## 5. Network layer

Native NIO, single `Selector` per worker thread. One worker owns the whole hot path:

```
read bytes -> frame decode -> packet decode -> state machine -> encode -> write
```

The gc-primitives 0.2.0 `Pool` contract states that a lease and its borrowed object belong
to the borrowing thread and must never cross threads. Therefore buffers and packet objects
are borrowed, used, and returned inside the same selector thread. Scaling is horizontal:
with N selector threads, connections are assigned by hash and **each thread owns an
independent pool set**. No shared pooled object crosses a thread boundary. `V020.
virtualThreadNote()` is recorded as documentation; virtual threads are not used in the hot
path of this milestone.

Frame guard: a VarInt frame length above 2 MiB (2,097,152 bytes) is rejected and the
connection is closed. At most 256 concurrent connections are accepted. Both limits are
configurable.

Non-blocking writes use a per-connection outbound queue and `OP_WRITE` interest; partial
frames are accumulated per connection. Encryption, when enabled, wraps the byte stream at
the connection level rather than at packet level.

## 6. gc-primitives 0.2.0 integration (full)

Everything available in 0.2.0 is wired in:

- `Pools.threadLocal(...)` / `Pool` / `Lease` / `Recycler` for the per-selector frame
  buffer pool; every `borrow()` sits in try-with-resources.
- `V020.PoolConfig` + `V020.loadConfig` + `V020.parseYamlSubset`: the `pools.*` section of
  `config.yaml`, `GC_PRIMITIVES_CONFIG` path override, and `GC_PRIMITIVES__<POOL>__<KEY>`
  environment overrides.
- `V020.PoolManager` with `enableHotReload(...)` and `reload()`; `register`, `get`, `close`.
- `V020.ManagedPool` with `borrow(tag)`, `prewarm`, `detectLeaks`, `metrics`, `stats`,
  `snapshot`, `applyConfig`, `assertNoActiveLeases`, and `LifecycleHooks` / `Recycler`
  reset hooks.
- `V020.MetricsCollector`, `metricsSnapshot`, the 16-field JSON shape, and `toJson`.
- `V020.LeakDetector` and lease sampling; `V020.memoryScope` behind `GC_PRIMITIVES_DIAG`.
- `V020.ShardedPool`, `V020.TwoLevelPool`, `V020.assertCombination`, and the frozen
  `COMBINATION_MATRIX`; the combination used here is checked at startup.
- `Pool.assertNoActiveLeases()` and `ManagedPool.assertNoActiveLeases()` are asserted in
  integration tests so a leaked lease fails CI.

Metrics output: every 30 s, `logs/metrics.json` is overwritten with the unified 16-field
snapshot (plus one entry per pool and the set of `UNVERIFIED` lookups).

## 7. Configuration

`config.yaml` at the repository root:

- `server.host`, `server.port`, `server.motd`, `server.online-mode` (default `true`),
  `server.session-url`, `server.max-frame-size`, `server.max-connections`,
  `server.keep-alive-interval-ms`, `server.keep-alive-timeout-ms`, `server.selector-threads`
- `pools.frames.*` / `pools.messages.*` in the gc-primitives YAML subset schema

The server-side keys are parsed with `V020.parseYamlSubset` (same parser, separate key
space). Hot reload polls file mtime; a changed `online-mode` affects only new connections,
and existing sessions keep their original `AuthProvider`.

## 8. Disconnect and keep-alive

- Below 26.1: bilingual text (English line + Chinese line), e.g.
  `This server requires Minecraft Java 26.1 or newer.` /
  `本服务器需要 Minecraft Java 26.1 或更高版本。`
- Keep-Alive: send every 15 s, disconnect if no matching response within 30 s.
- Status: English MOTD `A 26.x protocol core`, version name, protocol number, player counts.

## 9. Build and CI

Single Maven module `net.de5.rainbowcrrsys:folio:0.1.0-SNAPSHOT`, `release 25`, shaded
executable jar. Dependencies: gc-primitives 0.2.0 (runtime), JUnit Jupiter 5.10.2 (test).

`.github/workflows/ci.yml`:

- Triggers: `push`, `pull_request`, `workflow_dispatch`
- Matrix: protocol version `26.1 / 26.2 / 26.3` x JDK `25 / 26`
- Steps: checkout, `actions/setup-java`, `mvn -B verify -Dfolio.protocol.version=...`
- Integration tests cover offline and online (mock session server) for each matrix cell,
  the full five-state walk, and a keep-alive hold window
- No token is stored in the repository or workflow; the runner's automatic token is used

Note on local verification: the authoring sandbox has Java 11 and no Maven/JDK 25, and its
network policy blocks toolchain downloads. Compilation and tests are therefore verified by
the GitHub Actions matrix, which is the agreed "build and test on GitHub, free tier" path.
Any compile failure in the first run is fixed in a follow-up commit.

## 10. Repository layout and commit plan

```
README.md
LICENSE
docs/DESIGN.md
.gitignore
config.yaml
pom.xml
.github/workflows/ci.yml
src/main/java/net/de5/rainbowcrrsys/folio/...
src/test/java/net/de5/rainbowcrrsys/folio/...
```

Commit 1: `docs: add design` — README, LICENSE, `.gitignore`, `docs/DESIGN.md`.
Commit 2: `feat: minimal 26.x protocol core` — `pom.xml`, `config.yaml`, CI, sources, tests.

Both commits are English Conventional-style messages. No credential, token, or key material
appears in any committed file, in git history, or in CI configuration.
