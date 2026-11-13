package net.de5.rainbowcrrsys.folio;

import net.de5.rainbowcrrsys.gcprimitives.V020;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side settings from the {@code server.*} keys of config.yaml.
 *
 * <p>The same gc-primitives 0.2.0 YAML subset parser is used for the {@code pools.*} keys,
 * so one file, one parser, and one hot-reload loop cover both key spaces.
 * A changed {@code online-mode} is picked up by new connections only; established sessions
 * keep the {@link AuthProvider} they started with.
 */
public final class ServerConfig {

    private static final String DEFAULT_SESSION_URL =
            "https://sessionserver.mojang.com/session/minecraft/hasJoined";

    private final Path path;
    private volatile long lastMtime = -1L;

    public volatile String host = "0.0.0.0";
    public volatile String motd = "A 26.x protocol core";
    public volatile String sessionUrl = DEFAULT_SESSION_URL;
    public volatile int port = 25565;
    public volatile int maxFrameSize = 2 * 1024 * 1024;
    public volatile int maxConnections = 256;
    public volatile int keepAliveIntervalMs = 15000;
    public volatile int keepAliveTimeoutMs = 30000;
    public volatile int selectorThreads = 1;
    public volatile boolean onlineMode = true;

    private ServerConfig(Path path) {
        this.path = path;
    }

    /** Test entry point: load from an explicit file, bypassing the env override. */
    public static ServerConfig fromPath(Path path) {
        ServerConfig cfg = new ServerConfig(path);
        cfg.reload();
        return cfg;
    }

    public static ServerConfig load() {
        String explicit = System.getenv(V020.CONFIG_ENV);
        Path path = Paths.get(explicit != null && !explicit.isBlank() ? explicit : "config.yaml");
        writeDefaultIfMissing(path);
        ServerConfig cfg = new ServerConfig(path);
        cfg.reload();
        return cfg;
    }

    /** A downloaded jar with no config.yaml still starts with sane, editable defaults. */
    private static void writeDefaultIfMissing(Path path) {
        if (Files.exists(path)) return;
        String text = "# folio runtime configuration. Generated on first accepted start.\n"
                + "# Parsed with the gc-primitives 0.2.0 YAML subset.\n"
                + "# GC_PRIMITIVES_CONFIG overrides this path; server keys also accept FOLIO_<KEY>.\n"
                + "\n"
                + "server:\n"
                + "  host: 0.0.0.0\n"
                + "  port: 25565\n"
                + "  motd: \"A 26.x protocol core\"\n"
                + "  online-mode: true\n"
                + "  session-url: \"https://sessionserver.mojang.com/session/minecraft/hasJoined\"\n"
                + "  max-frame-size: 2097152\n"
                + "  max-connections: 256\n"
                + "  keep-alive-interval-ms: 15000\n"
                + "  keep-alive-timeout-ms: 30000\n"
                + "  selector-threads: 1\n"
                + "\n"
                + "pools:\n"
                + "  frames:\n"
                + "    max_size: 64\n"
                + "    on_full: drop_oldest\n"
                + "    leak_detection: true\n"
                + "    leak_timeout_ms: 3000\n"
                + "    prewarm: 8\n"
                + "  messages:\n"
                + "    max_size: 64\n"
                + "    on_full: drop_oldest\n"
                + "    leak_detection: true\n"
                + "    leak_timeout_ms: 3000\n"
                + "    prewarm: 8\n";
        try {
            Files.writeString(path, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.getLogger("folio.config").log(System.Logger.Level.INFO,
                    "config.yaml written with defaults next to the jar");
        } catch (IOException e) {
            System.getLogger("folio.config").log(System.Logger.Level.WARNING,
                    "config.yaml could not be written; using built-in defaults", e);
        }
    }

    public Path path() {
        return path;
    }

    public void reload() {
        if (Files.isRegularFile(path)) {
            try {
                Map<String, String> flat = V020.parseYamlSubset(Files.readString(path));
                host = str(flat, "host", host);
                motd = str(flat, "motd", motd);
                sessionUrl = str(flat, "session-url", sessionUrl);
                port = num(flat, "port", port);
                maxFrameSize = num(flat, "max-frame-size", maxFrameSize);
                maxConnections = num(flat, "max-connections", maxConnections);
                keepAliveIntervalMs = num(flat, "keep-alive-interval-ms", keepAliveIntervalMs);
                keepAliveTimeoutMs = num(flat, "keep-alive-timeout-ms", keepAliveTimeoutMs);
                selectorThreads = Math.max(1, num(flat, "selector-threads", selectorThreads));
                onlineMode = bool(flat, "online-mode", onlineMode);
            } catch (IOException e) {
                throw new IllegalStateException("failed to read config: " + path, e);
            }
        }
        lastMtime = mtime();
    }

    /** Returns true when the file changed and the settings were reloaded. */
    public boolean reloadIfChanged() {
        long m = mtime();
        if (m < 0 || m == lastMtime) return false;
        reload();
        return true;
    }

    private long mtime() {
        try {
            return Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toMillis() : -1L;
        } catch (IOException e) {
            return -1L;
        }
    }

    private static String str(Map<String, String> flat, String key, String fallback) {
        String env = System.getenv("FOLIO_" + key.toUpperCase(Locale.ROOT).replace('-', '_'));
        if (env != null && !env.isBlank()) return env;
        String value = flat.get("server." + key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int num(Map<String, String> flat, String key, int fallback) {
        String env = System.getenv("FOLIO_" + key.toUpperCase(Locale.ROOT).replace('-', '_'));
        String raw = env != null && !env.isBlank() ? env : flat.get("server." + key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean bool(Map<String, String> flat, String key, boolean fallback) {
        String env = System.getenv("FOLIO_" + key.toUpperCase(Locale.ROOT).replace('-', '_'));
        String raw = env != null && !env.isBlank() ? env : flat.get("server." + key);
        if (raw == null || raw.isBlank()) return fallback;
        return raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("yes");
    }
}
