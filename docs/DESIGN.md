# folio — design

Status: design for the delivered core. Repository content is English; the only non-ASCII
user-facing string in the protocol is the deliberately bilingual version-gate disconnect
message. Two license filenames are non-ASCII (`许可.txt`) by explicit request; their text
is the English Apache-2.0 license. `eula.txt` and its content are plain ASCII, matching the
official server's format.

## 1. Scope

Goal: a from-scratch protocol-level core for Minecraft Java Edition 26.x that a scripted
client can drive through every connection state and keep alive. Native NIO, no Netty, no
Paper/Spigot code, no world simulation.

In scope:

- VarInt framing, packet IDs, five connection states
- 26.1 / 26.2 / 26.3 through one table plus a resolved difference map
- Status response (MOTD, version, player counts), Ping/Pong
- Offline and online authentication, both complete
- Encryption (RSA + AES/CFB8) and `hasJoined` session verification
- Full Configuration negotiation: Known Packs round trip, Feature Flags, Registry Data for
  all 31 synchronized registries, Update Tags, Finish Configuration
- Full Play join sequence: complete `login` packet, position sync, mandatory Game Event 13,
  chunk centre, one all-air chunk, player-list / health / experience / abilities state
- Keep-Alive (15 s send / 30 s timeout)
- Full gc-primitives 0.2.0 integration
- Apache-2.0 license text in the repo and inside the shaded jar
- Vanilla-style two-start EULA gate (`eula.txt`, `eula=false` -> `true`)
- Auto-generated `config.yaml` on the first accepted start, so a bare jar runs as-is
- GitHub Actions matrix CI, a two-start startup job, and a downloadable shaded jar artifact

Out of scope: terrain generation, entities, inventory, chat commands, redstone, plugin API,
resource packs, full registry NBT inline data, and 26.4.

Acceptance is a protocol-level integration test on GitHub Actions (free tier). A real
vanilla client join and a real paid-account `hasJoined` round trip are **not** verified in
this environment: there is no 26.x game client and no account here. The sequence is written
from the official protocol page and needs one local run to close that last mile. This is
stated plainly rather than claimed as done.

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

Difference handling: `PacketIds` holds one baseline map and one explicit override map per
version. Play IDs for 775 and 776 are **resolved** to the 777 baseline values rather than
left unverified, using this evidence:

- 775 has 69 serverbound Play packets, the same count as 777.
- The published corrected 775 IDs (`custom_payload` 0x16, `client_information` 0x0E,
  `keep_alive` 0x1C, `chat_session_update` 0x0A) match 777 exactly.
- 775 has 141 clientbound Play packets; 777 has 144, with the three extras appended at
  0x8D–0x8F, so the first 141 share their IDs.
- 776 sits between 775 and 777 in the same registration interval, and its key IDs are
  resolved by that interval.

An override may still be `UNVERIFIED` (-1) for a future drop such as 26.4; in that case the
lookup falls back to the baseline ID and records the key in `logs/metrics.json` instead of
inventing a number. Handshaking / Status / Login / Configuration entries are shared across
all three versions.

Consequence: the CI matrix stays green and no lookup is reported as unverified. This is a
documented inference, not a value read back from a live 26.1/26.2 client.

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
  CONFIGURATION:
           C -> S select_known_packs [minecraft:core @ game version]
           S -> C select_known_packs (ack)
           C -> S update_enabled_features [minecraft:vanilla]
           C -> S registry_data x 31 (entry IDs, inline NBT omitted)
           C -> S update_tags (empty)
           C -> S finish_configuration
           S -> C finish_configuration
  PLAY:    login (full CommonPlayerSpawnInfo)
           player_abilities, set_held_slot, player_info_update,
           set_default_spawn_position, player_position (teleport id 0),
           game_event(13), set_center_chunk, render/simulation distance,
           chunk_batch_start, level_chunk_with_light (one all-air chunk),
           chunk_batch_finished, set_health, set_experience, system_chat
           + Keep-Alive
```

No `Set Compression` packet is sent, so the uncompressed frame format applies throughout.
In 26.x a Text Component is an NBT tag rather than a JSON string; `Nbt` writes a plain
`TAG_String` root for Configuration / Play disconnects and system chat, while Login
Disconnect and the Status JSON response keep their JSON-string forms.

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

An optional `registries.json` next to `config.yaml` replaces the built-in minimal registry
table. It is a JSON object of registry id -> ordered entry id array, parsed by a small
zero-dependency reader in `Registries`. Export it from the official server jar with
`java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports`. This is the
supported path to a full vanilla registry view.

### 7.1 Startup files and the EULA gate

`Folio.main` runs in this order:

```
License.ensure()   -> writes 许可.txt from /LICENSE.txt inside the jar (if missing)
Eula.ensure()      -> writes eula.txt (eula=false) and returns false on the first start
                      -> false: log guidance and return, no socket, no config, no logs/
ServerConfig.load()-> writes config.yaml with defaults (if missing), then parses it
GcRuntime.start()  -> schedules logs/metrics.json at +30 s, every 30 s, overwrite
NioServer.start()  -> binds the port, logs "folio listening on port ..."
```

Files written by the server itself, by phase:

| Phase | File | Behaviour |
| --- | --- | --- |
| First start | `许可.txt` | Apache-2.0 text, only if absent |
| First start | `eula.txt` | `eula=false`, only if absent; never overwritten |
| Accepted start | `config.yaml` | defaults, only if absent |
| Accepted start, +30 s | `logs/metrics.json` | overwritten every 30 s |

No `world/`, `dimensions/`, `server.properties`, player/ban/whitelist JSON, or
`logs/latest.log` is ever written. `registries.json` is optional input, never output.

`Eula.accepted` skips comments and blank lines, tolerates a UTF-8 BOM, case, and spaces
around `=`, and refuses on any missing or non-`true` value. The gate applies to the
Minecraft EULA; folio's own source license remains Apache-2.0.

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
  the full five-state walk, the EULA gate unit tests, and a keep-alive hold window
- A `startup` job packages the shaded jar, runs it in a clean directory twice (first start
  must leave only `许可.txt` + `eula.txt` and bind no port; second start must generate
  `config.yaml` and log the listening line), and uploads `folio.jar` as the `folio-jar`
  artifact so a runnable binary is available without a local Maven build
- No token is stored in the repository or workflow; the runner's automatic token is used

Note on local verification: the authoring sandbox has Java 11 and no Maven/JDK 25, so the
JDK 25 `mvn verify` is verified by the GitHub Actions matrix (the agreed "build and test on
GitHub, free tier" path). Locally, all main and test sources are compiled with the JDK 11
compiler module against the real gc-primitives 0.2.0 sources, followed by an end-to-end
smoke run of the full state machine, and a two-start `java -cp ... Folio` check plus Status
ping (equivalent to `java -jar` on the shaded artifact). That check confirms the EULA gate,
the `许可.txt` and `config.yaml` writes, and the 30 s `logs/metrics.json` write.

## 10. Repository layout and commit plan

```
README.md
LICENSE
LICENSE.txt
许可.txt
docs/DESIGN.md
.gitignore
config.yaml
pom.xml
.github/workflows/ci.yml
src/main/resources/LICENSE.txt
src/main/java/net/de5/rainbowcrrsys/folio/...
src/test/java/net/de5/rainbowcrrsys/folio/...
```

Commits: `docs: add design`, `feat: minimal 26.x protocol core`,
`feat: complete configuration and join sequence`, and
`feat: vanilla-style EULA gate and first-run config`. All English Conventional-style
messages. No credential, token, or key material appears in any committed file, in git
history, or in CI configuration.

Both startup phases are verified locally with `java -cp ... Folio` plus a Status ping
(equivalent to `java -jar` on the shaded artifact); the JDK 25 Maven build itself is
verified by the GitHub Actions matrix, including the two-start startup job.
