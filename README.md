# folio

A from-scratch Minecraft: Java Edition protocol core. No Paper, no Spigot, no Netty — just
the JDK, native NIO, and a small allocation-reducing pooling library.

`folio` is the codename. It is a short English word meaning a single sheet / leaf of paper,
chosen to sit next to "Paper" without being a fork of it.

## What this is

`folio` implements the 26.x wire protocol directly: VarInt framing, packet IDs, the
Handshaking / Status / Login / Configuration / Play state machine, encryption, session
verification, registry negotiation, the full join sequence, and keep-alives.

This milestone completes the whole protocol path a client walks to reach a world:

- Configuration does the full vanilla round trip: `select_known_packs` -> Feature Flags ->
  `registry_data` (all 31 synchronized registries) -> `update_tags` -> `finish_configuration`.
- Play sends the complete `login` packet (including the full CommonPlayerSpawnInfo block),
  position sync with teleport confirmation, the mandatory "Start waiting for chunks"
  Game Event, chunk centre, one all-air chunk, player list / health / experience / abilities
  state, and keep-alives.

The world is a single empty chunk. There is no terrain, no entities, no inventory, no chat
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

The three drops share one packet table with a per-version difference map. Play IDs for 775
and 776 are resolved to the 777 values: protocol 775 has the same 69 serverbound Play
packets as 777 and its published corrected IDs (custom_payload 0x16, client_information
0x0E, keep_alive 0x1C, chat_session_update 0x0A) match 777 exactly; 775's 141 clientbound
packets are the first 141 of 777's 144. 776 sits in the same registration interval. These
are documented inferences, not values read back from a live 26.1/26.2 client.

## Requirements

- Java 25 or newer (26.1 itself requires Java 25)
- Maven 3.9+ to build

Runtime dependency: `net.de5.rainbowcrrsys:gc-primitives:0.2.0` from Maven Central.
Tests use JUnit 5.10.2. There are no other third-party dependencies.

## Build and run

```sh
mvn -B package
java -jar target/folio-0.1.0-SNAPSHOT.jar
```

This is a shaded executable jar, so `java -jar` is the whole startup path. On first start it
writes `许可.txt` (the Apache-2.0 text) next to the working directory; the same text is
bundled inside the jar and committed at the repository root. There is no EULA acceptance
step.

`config.yaml` sits next to the jar. Defaults: port `25565`, MOTD `A 26.x protocol core`,
`online-mode: true`. For a local smoke test set `online-mode: false` so no Mojang session
lookup is attempted. `GC_PRIMITIVES_CONFIG` overrides the config path; pool settings and
server settings are hot-reloaded, and a changed `online-mode` applies to **new connections
only**.

## Registries and real clients

The built-in registry table is intentionally small: ordered entry IDs for all 31
synchronized registries, with NBT omitted so the client resolves definitions from the
`minecraft:core` pack it already has. That is enough to complete the protocol handshake and
to reference dimension_type / biome ID 0.

For a full vanilla data set, export the registries from the official server jar and place
`registries.json` next to `config.yaml`:

```sh
java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports
# then copy generated/reports/registries.json next to config.yaml
```

The file is a JSON object mapping each registry id to its ordered array of entry ids,
for example:

```json
{
  "minecraft:dimension_type": ["minecraft:overworld", "minecraft:the_nether"],
  "minecraft:worldgen/biome": ["minecraft:the_void", "minecraft:plains"]
}
```

Unknown keys are appended; known keys replace the built-in list. The parser is a tiny
zero-dependency reader, not a full JSON library.

## Authentication

`online-mode` defaults to `true`. Both paths are fully implemented:

- **Online**: a 1024-bit RSA keypair is generated in memory at startup. Encryption Request
  (`hello`), AES/CFB8 stream encryption, and a `hasJoined` GET against the configured
  session URL are all performed. The default URL is the real
  `sessionserver.mojang.com/session/minecraft/hasJoined`. In CI it points at a local mock
  session server.
- **Offline**: Login Start goes straight to Login Success with a UUIDv3 derived from
  `OfflinePlayer:<name>`.

The server-side verification chain is complete; end-to-end verification with a real
paid account and a vanilla client is a local step (see Limitations).

## Observability

Every 30 seconds the 16-field unified metrics snapshot is written to `logs/metrics.json`.
Logs go through `java.lang.System.Logger`, so the runtime stays dependency-free.
Set `GC_PRIMITIVES_DIAG=1` to enable `memory_scope` diagnostics.

## Continuous integration

GitHub Actions runs `mvn -B verify` on every push, pull request, and manual dispatch, over a
matrix of three protocol versions and two JDKs (25 and 26). The integration test drives a
scripted client through all five states, the registry negotiation, the full Play join
sequence, and a keep-alive hold, for both offline and online (mock session) modes. No
credentials are stored in the repository or in CI configuration.

## Limitations

- **A real vanilla client join is not verified here.** CI uses a scripted protocol client,
  and this environment has neither a 26.x game client nor a paid account. The full sequence
  is written from the official protocol page; the last mile — a real client rendering the
  world, and a real account completing `hasJoined` — needs one local run with JDK 25+.
- The built-in registry set is minimal. A vanilla client may require the full exported
  `registries.json` plus complete NBT inline data to finish resolving every registry.
- The Play IDs for 775/776 are documented inferences, as described above.
- The world is one empty chunk. There is no terrain generation, entity simulation,
  inventory, chat, commands, or plugin API.

## License

Apache License 2.0. See [LICENSE](LICENSE), [LICENSE.txt](LICENSE.txt) and
[许可.txt](许可.txt).
