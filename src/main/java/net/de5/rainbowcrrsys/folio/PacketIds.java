package net.de5.rainbowcrrsys.folio;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One shared packet table plus a per-version difference map.
 *
 * <p>The baseline is the 26.3 / protocol 777 table from the official protocol page. Only the
 * packets this core emits or consumes are registered; the remaining ~250 packets are
 * deliberately not hardcoded.
 *
 * <p>Play IDs for 775 and 776 are now resolved rather than {@code UNVERIFIED}. Evidence:
 * protocol 775 has the same 69 serverbound Play packets as 777, and the published corrected
 * IDs for 775 (custom_payload 0x16, client_information 0x0E, keep_alive 0x1C,
 * chat_session_update 0x0A) match 777 exactly; 775's 141 clientbound packets are the first
 * 141 of 777's 144. 776 sits between the two and its key IDs are resolved by the same
 * registration interval. These are documented inferences, not values read back from a live
 * 26.1/26.2 client; see docs/DESIGN.md. An {@link #UNVERIFIED} override still falls back to
 * the baseline and records the key, for future versions such as 26.4.
 */
public final class PacketIds {

    public static final int UNVERIFIED = -1;

    public static final String HS_S_INTENTION = "HS.S.intention";
    public static final String STATUS_C_RESPONSE = "STATUS.C.status_response";
    public static final String STATUS_C_PONG = "STATUS.C.pong_response";
    public static final String STATUS_S_REQUEST = "STATUS.S.status_request";
    public static final String STATUS_S_PING = "STATUS.S.ping_request";
    public static final String LOGIN_C_DISCONNECT = "LOGIN.C.login_disconnect";
    public static final String LOGIN_C_HELLO = "LOGIN.C.hello";
    public static final String LOGIN_C_FINISHED = "LOGIN.C.login_finished";
    public static final String LOGIN_C_COMPRESSION = "LOGIN.C.login_compression";
    public static final String LOGIN_S_HELLO = "LOGIN.S.hello";
    public static final String LOGIN_S_KEY = "LOGIN.S.key";
    public static final String LOGIN_S_ACK = "LOGIN.S.login_acknowledged";

    public static final String CONFIG_C_COOKIE_REQUEST = "CONFIG.C.cookie_request";
    public static final String CONFIG_C_CUSTOM_PAYLOAD = "CONFIG.C.custom_payload";
    public static final String CONFIG_C_DISCONNECT = "CONFIG.C.disconnect";
    public static final String CONFIG_C_FINISH = "CONFIG.C.finish_configuration";
    public static final String CONFIG_C_KEEP_ALIVE = "CONFIG.C.keep_alive";
    public static final String CONFIG_C_REGISTRY_DATA = "CONFIG.C.registry_data";
    public static final String CONFIG_C_FEATURE_FLAGS = "CONFIG.C.update_enabled_features";
    public static final String CONFIG_C_UPDATE_TAGS = "CONFIG.C.update_tags";
    public static final String CONFIG_C_KNOWN_PACKS = "CONFIG.C.select_known_packs";

    public static final String CONFIG_S_CLIENT_INFO = "CONFIG.S.client_information";
    public static final String CONFIG_S_CUSTOM_PAYLOAD = "CONFIG.S.custom_payload";
    public static final String CONFIG_S_FINISH = "CONFIG.S.finish_configuration";
    public static final String CONFIG_S_KEEP_ALIVE = "CONFIG.S.keep_alive";
    public static final String CONFIG_S_KNOWN_PACKS = "CONFIG.S.select_known_packs";

    public static final String PLAY_C_CHUNK_BATCH_FINISHED = "PLAY.C.chunk_batch_finished";
    public static final String PLAY_C_CHUNK_BATCH_START = "PLAY.C.chunk_batch_start";
    public static final String PLAY_C_DISCONNECT = "PLAY.C.disconnect";
    public static final String PLAY_C_GAME_EVENT = "PLAY.C.game_event";
    public static final String PLAY_C_KEEP_ALIVE = "PLAY.C.keep_alive";
    public static final String PLAY_C_CHUNK_DATA = "PLAY.C.level_chunk_with_light";
    public static final String PLAY_C_LOGIN = "PLAY.C.login";
    public static final String PLAY_C_PLAYER_ABILITIES = "PLAY.C.player_abilities";
    public static final String PLAY_C_PLAYER_INFO_UPDATE = "PLAY.C.player_info_update";
    public static final String PLAY_C_PLAYER_POSITION = "PLAY.C.player_position";
    public static final String PLAY_C_SERVER_DATA = "PLAY.C.server_data";
    public static final String PLAY_C_SET_CENTER_CHUNK = "PLAY.C.set_chunk_cache_center";
    public static final String PLAY_C_SET_RENDER_DISTANCE = "PLAY.C.set_chunk_cache_radius";
    public static final String PLAY_C_SET_DEFAULT_SPAWN = "PLAY.C.set_default_spawn_position";
    public static final String PLAY_C_SET_EXPERIENCE = "PLAY.C.set_experience";
    public static final String PLAY_C_SET_HEALTH = "PLAY.C.set_health";
    public static final String PLAY_C_SET_HELD_SLOT = "PLAY.C.set_held_slot";
    public static final String PLAY_C_SET_SIMULATION_DISTANCE = "PLAY.C.set_simulation_distance";
    public static final String PLAY_C_UPDATE_TIME = "PLAY.C.set_time";
    public static final String PLAY_C_SYSTEM_CHAT = "PLAY.C.system_chat";

    public static final String PLAY_S_ACCEPT_TELEPORTATION = "PLAY.S.accept_teleportation";
    public static final String PLAY_S_CHUNK_BATCH_RECEIVED = "PLAY.S.chunk_batch_received";
    public static final String PLAY_S_CLIENT_COMMAND = "PLAY.S.client_command";
    public static final String PLAY_S_CLIENT_TICK_END = "PLAY.S.client_tick_end";
    public static final String PLAY_S_CLIENT_INFORMATION = "PLAY.S.client_information";
    public static final String PLAY_S_KEEP_ALIVE = "PLAY.S.keep_alive";
    public static final String PLAY_S_MOVE_POS_ROT = "PLAY.S.move_player_pos_rot";
    public static final String PLAY_S_PLAYER_COMMAND = "PLAY.S.player_command";
    public static final String PLAY_S_PLAYER_LOADED = "PLAY.S.player_loaded";

    private static final Map<String, Integer> BASELINE = new HashMap<>();

    static {
        put(HS_S_INTENTION, 0x00);
        put(STATUS_C_RESPONSE, 0x00);
        put(STATUS_C_PONG, 0x01);
        put(STATUS_S_REQUEST, 0x00);
        put(STATUS_S_PING, 0x01);
        put(LOGIN_C_DISCONNECT, 0x00);
        put(LOGIN_C_HELLO, 0x01);
        put(LOGIN_C_FINISHED, 0x02);
        put(LOGIN_C_COMPRESSION, 0x03);
        put(LOGIN_S_HELLO, 0x00);
        put(LOGIN_S_KEY, 0x01);
        put(LOGIN_S_ACK, 0x03);

        put(CONFIG_C_COOKIE_REQUEST, 0x00);
        put(CONFIG_C_CUSTOM_PAYLOAD, 0x01);
        put(CONFIG_C_DISCONNECT, 0x02);
        put(CONFIG_C_FINISH, 0x03);
        put(CONFIG_C_KEEP_ALIVE, 0x04);
        put(CONFIG_C_REGISTRY_DATA, 0x07);
        put(CONFIG_C_FEATURE_FLAGS, 0x0D);
        put(CONFIG_C_UPDATE_TAGS, 0x0E);
        put(CONFIG_C_KNOWN_PACKS, 0x0F);
        put(CONFIG_S_CLIENT_INFO, 0x00);
        put(CONFIG_S_CUSTOM_PAYLOAD, 0x02);
        put(CONFIG_S_FINISH, 0x03);
        put(CONFIG_S_KEEP_ALIVE, 0x04);
        put(CONFIG_S_KNOWN_PACKS, 0x07);

        put(PLAY_C_CHUNK_BATCH_FINISHED, 0x0B);
        put(PLAY_C_CHUNK_BATCH_START, 0x0C);
        put(PLAY_C_DISCONNECT, 0x20);
        put(PLAY_C_GAME_EVENT, 0x27);
        put(PLAY_C_KEEP_ALIVE, 0x2D);
        put(PLAY_C_CHUNK_DATA, 0x2E);
        put(PLAY_C_LOGIN, 0x32);
        put(PLAY_C_PLAYER_ABILITIES, 0x41);
        put(PLAY_C_PLAYER_INFO_UPDATE, 0x47);
        put(PLAY_C_PLAYER_POSITION, 0x49);
        put(PLAY_C_SERVER_DATA, 0x58);
        put(PLAY_C_SET_CENTER_CHUNK, 0x60);
        put(PLAY_C_SET_RENDER_DISTANCE, 0x61);
        put(PLAY_C_SET_DEFAULT_SPAWN, 0x63);
        put(PLAY_C_SET_EXPERIENCE, 0x69);
        put(PLAY_C_SET_HEALTH, 0x6A);
        put(PLAY_C_SET_HELD_SLOT, 0x6B);
        put(PLAY_C_SET_SIMULATION_DISTANCE, 0x71);
        put(PLAY_C_UPDATE_TIME, 0x73);
        put(PLAY_C_SYSTEM_CHAT, 0x7C);

        put(PLAY_S_ACCEPT_TELEPORTATION, 0x00);
        put(PLAY_S_CHUNK_BATCH_RECEIVED, 0x0B);
        put(PLAY_S_CLIENT_COMMAND, 0x0C);
        put(PLAY_S_CLIENT_TICK_END, 0x0D);
        put(PLAY_S_CLIENT_INFORMATION, 0x0E);
        put(PLAY_S_KEEP_ALIVE, 0x1C);
        put(PLAY_S_MOVE_POS_ROT, 0x1F);
        put(PLAY_S_PLAYER_COMMAND, 0x2A);
        put(PLAY_S_PLAYER_LOADED, 0x2C);
    }

    private static void put(String key, int id) {
        BASELINE.put(key, id);
    }

    /**
     * Explicit resolution for 775 / 776. Every key resolves to the 777 baseline value, which
     * is the documented inference above. Kept as a separate map so a future drop can override
     * a single ID without touching the baseline.
     */
    private static final Map<ProtocolVersion, Map<String, Integer>> OVERRIDES = new LinkedHashMap<>();

    static {
        for (ProtocolVersion v : new ProtocolVersion[]{ProtocolVersion.V26_1, ProtocolVersion.V26_2}) {
            Map<String, Integer> m = new HashMap<>();
            for (String key : BASELINE.keySet()) {
                if (key.startsWith("PLAY.")) m.put(key, BASELINE.get(key));
            }
            OVERRIDES.put(v, Collections.unmodifiableMap(m));
        }
    }

    private static final Set<String> UNVERIFIED_LOOKUPS = ConcurrentHashMap.newKeySet();

    private PacketIds() {
    }

    public static int baseline(String key) {
        Integer id = BASELINE.get(key);
        if (id == null) throw new IllegalArgumentException("unregistered packet key: " + key);
        return id;
    }

    public static int id(ProtocolVersion version, String key) {
        Map<String, Integer> overrides = OVERRIDES.get(version);
        if (overrides != null) {
            Integer override = overrides.get(key);
            if (override != null) {
                if (override == UNVERIFIED) {
                    UNVERIFIED_LOOKUPS.add(version.display() + "." + key);
                    return baseline(key);
                }
                return override;
            }
        }
        return baseline(key);
    }

    public static Set<String> unverifiedLookups() {
        return Collections.unmodifiableSet(UNVERIFIED_LOOKUPS);
    }

    public static int varIntSize(int value) {
        int n = 0;
        int v = value;
        do {
            n++;
            v >>>= 7;
        } while (v != 0);
        return n;
    }
}
