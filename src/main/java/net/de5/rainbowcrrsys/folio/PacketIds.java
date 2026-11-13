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
 * packets this milestone emits or consumes are registered; the remaining ~250 packets are
 * deliberately not hardcoded.
 *
 * <p>An override of {@link #UNVERIFIED} means the exact ID for that version could not be
 * confirmed. The lookup falls back to the baseline and records the key instead of inventing
 * a number. All Play IDs for 775 and 776 are marked UNVERIFIED in this milestone; see
 * docs/DESIGN.md. Confirmed signal for 775: ~15 new serverbound Play packets shifted later
 * IDs (custom_payload 0x0A -> 0x16, chat_session_update 0x06 -> 0x0A,
 * keep_alive 0x10 -> 0x1C in one published mapping), and its clientbound Play table is
 * shorter than 777's. Those values are documentation, not registered IDs.
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
    public static final String CONFIG_C_DISCONNECT = "CONFIG.C.disconnect";
    public static final String CONFIG_C_FINISH = "CONFIG.C.finish_configuration";
    public static final String CONFIG_C_KEEP_ALIVE = "CONFIG.C.keep_alive";
    public static final String CONFIG_S_CLIENT_INFO = "CONFIG.S.client_information";
    public static final String CONFIG_S_FINISH = "CONFIG.S.finish_configuration";
    public static final String CONFIG_S_KEEP_ALIVE = "CONFIG.S.keep_alive";
    public static final String PLAY_C_KEEP_ALIVE = "PLAY.C.keep_alive";
    public static final String PLAY_C_LOGIN = "PLAY.C.login";
    public static final String PLAY_C_DISCONNECT = "PLAY.C.disconnect";
    public static final String PLAY_S_KEEP_ALIVE = "PLAY.S.keep_alive";

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
        put(CONFIG_C_DISCONNECT, 0x02);
        put(CONFIG_C_FINISH, 0x03);
        put(CONFIG_C_KEEP_ALIVE, 0x04);
        put(CONFIG_S_CLIENT_INFO, 0x00);
        put(CONFIG_S_FINISH, 0x03);
        put(CONFIG_S_KEEP_ALIVE, 0x04);
        put(PLAY_C_KEEP_ALIVE, 0x2D);
        put(PLAY_C_LOGIN, 0x32);
        put(PLAY_C_DISCONNECT, 0x20);
        put(PLAY_S_KEEP_ALIVE, 0x1C);
    }

    private static void put(String key, int id) {
        BASELINE.put(key, id);
    }

    private static final Map<ProtocolVersion, Map<String, Integer>> OVERRIDES = new LinkedHashMap<>();

    static {
        for (ProtocolVersion v : new ProtocolVersion[]{ProtocolVersion.V26_1, ProtocolVersion.V26_2}) {
            Map<String, Integer> m = new HashMap<>();
            m.put(PLAY_C_KEEP_ALIVE, UNVERIFIED);
            m.put(PLAY_C_LOGIN, UNVERIFIED);
            m.put(PLAY_C_DISCONNECT, UNVERIFIED);
            m.put(PLAY_S_KEEP_ALIVE, UNVERIFIED);
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
