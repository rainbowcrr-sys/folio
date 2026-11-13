package net.de5.rainbowcrrsys.folio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration-phase registry synchronisation.
 *
 * <p>Vanilla requires a {@code select_known_packs} round trip before any registry data. When
 * both peers agree on {@code minecraft:core} with the game version as the pack version, the
 * server may send entry IDs without inline NBT and the client resolves the definitions from
 * its own pack. Registry Data is then followed by Update Tags and Finish Configuration;
 * the client validates everything before acknowledging.
 *
 * <p>The built-in table is a deliberately small but ordered set: it is enough to complete the
 * protocol handshake and to reference dimension_type / biome ID 0. For a full vanilla client
 * experience, drop a {@code registries.json} next to config.yaml (a JSON object mapping each
 * registry id to its ordered array of entry ids, as emitted by the official server's
 * {@code --reports} data generator). It is parsed with a tiny zero-dependency reader.
 */
public final class Registries {

    public static final String CORE_NAMESPACE = "minecraft";
    public static final String CORE_ID = "core";

    private static final String[] ORDER = {
            "minecraft:worldgen/biome",
            "minecraft:chat_type",
            "minecraft:trim_pattern",
            "minecraft:trim_material",
            "minecraft:wolf_variant",
            "minecraft:wolf_sound_variant",
            "minecraft:pig_variant",
            "minecraft:pig_sound_variant",
            "minecraft:frog_variant",
            "minecraft:cat_variant",
            "minecraft:cat_sound_variant",
            "minecraft:cow_variant",
            "minecraft:cow_sound_variant",
            "minecraft:chicken_variant",
            "minecraft:chicken_sound_variant",
            "minecraft:painting_variant",
            "minecraft:sulfur_cube_archetype",
            "minecraft:dimension_type",
            "minecraft:damage_type",
            "minecraft:banner_pattern",
            "minecraft:enchantment",
            "minecraft:jukebox_song",
            "minecraft:instrument",
            "minecraft:test_environment",
            "minecraft:test_instance",
            "minecraft:dialog",
            "minecraft:world_clock",
            "minecraft:timeline",
            "minecraft:decorated_pot_pattern",
            "minecraft:block_transformer",
            "minecraft:worldgen/block_state_provider"
    };

    private static final Map<String, List<String>> BUILTIN = new LinkedHashMap<>();

    static {
        BUILTIN.put("minecraft:worldgen/biome", List.of("minecraft:plains"));
        BUILTIN.put("minecraft:chat_type", List.of("minecraft:chat"));
        BUILTIN.put("minecraft:trim_pattern", List.of("minecraft:sentry"));
        BUILTIN.put("minecraft:trim_material", List.of("minecraft:quartz"));
        BUILTIN.put("minecraft:wolf_variant", List.of("minecraft:pale"));
        BUILTIN.put("minecraft:wolf_sound_variant", List.of("minecraft:default"));
        BUILTIN.put("minecraft:pig_variant", List.of("minecraft:temperate"));
        BUILTIN.put("minecraft:pig_sound_variant", List.of("minecraft:default"));
        BUILTIN.put("minecraft:frog_variant", List.of("minecraft:temperate"));
        BUILTIN.put("minecraft:cat_variant", List.of("minecraft:tabby"));
        BUILTIN.put("minecraft:cat_sound_variant", List.of("minecraft:default"));
        BUILTIN.put("minecraft:cow_variant", List.of("minecraft:temperate"));
        BUILTIN.put("minecraft:cow_sound_variant", List.of("minecraft:default"));
        BUILTIN.put("minecraft:chicken_variant", List.of("minecraft:temperate"));
        BUILTIN.put("minecraft:chicken_sound_variant", List.of("minecraft:default"));
        BUILTIN.put("minecraft:painting_variant", List.of("minecraft:kebab"));
        BUILTIN.put("minecraft:sulfur_cube_archetype", List.of("minecraft:default"));
        BUILTIN.put("minecraft:dimension_type", List.of(
                "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"));
        BUILTIN.put("minecraft:damage_type", List.of("minecraft:generic", "minecraft:generic_kill"));
        BUILTIN.put("minecraft:banner_pattern", List.of("minecraft:base"));
        BUILTIN.put("minecraft:enchantment", List.of("minecraft:protection"));
        BUILTIN.put("minecraft:jukebox_song", List.of("minecraft:13"));
        BUILTIN.put("minecraft:instrument", List.of("minecraft:ponder_goat_horn"));
        BUILTIN.put("minecraft:test_environment", List.of("minecraft:default"));
        BUILTIN.put("minecraft:test_instance", List.of("minecraft:default"));
        BUILTIN.put("minecraft:dialog", List.of("minecraft:default"));
        BUILTIN.put("minecraft:world_clock", List.of("minecraft:default"));
        BUILTIN.put("minecraft:timeline", List.of("minecraft:default"));
        BUILTIN.put("minecraft:decorated_pot_pattern", List.of("minecraft:default"));
        BUILTIN.put("minecraft:block_transformer", List.of("minecraft:default"));
        BUILTIN.put("minecraft:worldgen/block_state_provider", List.of("minecraft:default"));
    }

    private Registries() {
    }

    /** Returns an ordered list of (registry id, ordered entry ids) pairs. */
    public static List<Registry> load() {
        Map<String, List<String>> custom = readOverride();
        List<Registry> out = new ArrayList<>();
        for (String id : ORDER) {
            List<String> entries = custom != null && custom.containsKey(id)
                    ? custom.get(id)
                    : BUILTIN.getOrDefault(id, List.of("minecraft:default"));
            out.add(new Registry(id, entries));
        }
        if (custom != null) {
            for (Map.Entry<String, List<String>> e : custom.entrySet()) {
                boolean known = false;
                for (Registry r : out) if (r.id().equals(e.getKey())) known = true;
                if (!known) out.add(new Registry(e.getKey(), e.getValue()));
            }
        }
        return out;
    }

    public static final class Registry {
        private final String id;
        private final List<String> entries;

        Registry(String id, List<String> entries) {
            this.id = id;
            this.entries = entries;
        }

        public String id() {
            return id;
        }

        public List<String> entries() {
            return entries;
        }
    }

    public static void writeKnownPacks(PacketBuffer b, ProtocolVersion version) {
        b.writeVarInt(1);
        b.writeString(CORE_NAMESPACE);
        b.writeString(CORE_ID);
        b.writeString(version.display());
    }

    public static void writeFeatureFlags(PacketBuffer b) {
        b.writeVarInt(1);
        b.writeString("minecraft:vanilla");
    }

    public static void writeRegistryData(PacketBuffer b, Registry registry) {
        b.writeString(registry.id());
        b.writeVarInt(registry.entries().size());
        for (String entry : registry.entries()) {
            b.writeString(entry);
            b.writeBoolean(false); // No inline NBT: resolve from minecraft:core.
        }
    }

    public static void writeUpdateTags(PacketBuffer b) {
        b.writeVarInt(0);
    }

    /** Optional external registry table, zero-dependency JSON object of string arrays. */
    private static Map<String, List<String>> readOverride() {
        Path path = Paths.get("registries.json");
        if (!Files.isRegularFile(path)) return null;
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return parseObjectOfArrays(text);
        } catch (IOException | RuntimeException e) {
            System.getLogger("folio.registries").log(System.Logger.Level.WARNING,
                    "registries.json ignored: " + e.getMessage());
            return null;
        }
    }

    private static Map<String, List<String>> parseObjectOfArrays(String text) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        int i = 0;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        if (i >= text.length() || text.charAt(i) != '{') throw new IllegalArgumentException("expected {");
        i++;
        while (i < text.length()) {
            while (i < text.length() && (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == ',')) i++;
            if (i < text.length() && text.charAt(i) == '}') break;
            if (i >= text.length() || text.charAt(i) != '"') throw new IllegalArgumentException("expected key");
            StringBuilder key = new StringBuilder();
            i = readJsonString(text, i, key);
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
            if (i >= text.length() || text.charAt(i) != ':') throw new IllegalArgumentException("expected :");
            i++;
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
            if (i >= text.length() || text.charAt(i) != '[') throw new IllegalArgumentException("expected [");
            i++;
            List<String> values = new ArrayList<>();
            while (i < text.length()) {
                while (i < text.length() && (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == ',')) i++;
                if (i < text.length() && text.charAt(i) == ']') { i++; break; }
                if (i >= text.length() || text.charAt(i) != '"') throw new IllegalArgumentException("expected entry");
                StringBuilder value = new StringBuilder();
                i = readJsonString(text, i, value);
                values.add(value.toString());
            }
            out.put(key.toString(), values);
        }
        return out;
    }

    private static int readJsonString(String text, int start, StringBuilder out) {
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i++);
            if (c == '"') return i;
            if (c == '\\' && i < text.length()) {
                char n = text.charAt(i++);
                switch (n) {
                    case 'n': out.append('\n'); break;
                    case 't': out.append('\t'); break;
                    case 'r': out.append('\r'); break;
                    default: out.append(n); break;
                }
            } else out.append(c);
        }
        throw new IllegalArgumentException("unterminated string");
    }
}
