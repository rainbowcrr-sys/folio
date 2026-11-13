package net.de5.rainbowcrrsys.folio;

import net.de5.rainbowcrrsys.gcprimitives.Lease;
import net.de5.rainbowcrrsys.gcprimitives.V020;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the full gc-primitives 0.2.0 surface wired into {@link GcRuntime}. */
class GcRuntimeTest {

    @Test
    void fullV020Surface() throws Exception {
        Path dir = Files.createTempDirectory("folio-gc-");
        Path config = dir.resolve("config.yaml");
        Files.writeString(config,
                "server:\n"
                        + "  online-mode: false\n"
                        + "pools:\n"
                        + "  frames:\n"
                        + "    max_size: 32\n"
                        + "    on_full: drop_oldest\n"
                        + "    leak_detection: true\n"
                        + "    prewarm: 4\n"
                        + "  messages:\n"
                        + "    max_size: 32\n");

        ServerConfig cfg = ServerConfig.fromPath(config);
        GcRuntime gc = GcRuntime.start(cfg);
        try {
            assertEquals(16, V020.METRICS_FIELDS.size());
            assertEquals(16, V020.metricsSnapshot().size());
            assertTrue(V020.metricsSnapshot().containsKey("object_lifetime_p50"));

            assertEquals(Boolean.TRUE, V020.COMBINATION_MATRIX.get("local").get("standalone"));
            assertEquals(Boolean.FALSE, V020.COMBINATION_MATRIX.get("local").get("shardable"));
            assertThrows(V020.CombinationError.class,
                    () -> V020.assertCombination(V020.PoolKind.LOCAL, "shardable"));

            Map<String, String> flat = V020.parseYamlSubset(
                    "pools:\n  frames:\n    max_size: 32\n");
            assertEquals("32", flat.get("pools.frames.max_size"));

            V020.PoolConfig loaded = V020.loadConfig(config.toString(), "frames");
            assertEquals(32L, loaded.maxSize);
            assertTrue(loaded.leakDetection);

            try (V020.ManagedLease<PacketBuffer> lease = gc.frames().borrow("test")) {
                lease.get().reset();
            }
            gc.frames().assertNoActiveLeases();
            assertTrue(gc.frames().metrics().containsKey("hit_rate"));
            assertTrue(gc.frames().snapshot().containsKey("config"));

            try (Lease<PacketBuffer> lease = gc.borrowFrame()) {
                lease.get().reset();
            }
            try (V020.ManagedLease<PacketBuffer> lease = gc.sharded().borrow()) {
                lease.get().reset();
            }
            try (V020.ManagedLease<PacketBuffer> lease = gc.twoLevel().borrow()) {
                lease.get().reset();
            }
            try (V020.MemoryScope scope = gc.scope("test")) {
                // No-op unless GC_PRIMITIVES_DIAG is set.
            }
            assertFalse(V020.virtualThreadNote().isBlank());

            String json = V020.toJson(gc.snapshot());
            assertTrue(json.contains("\"unified_fields\""));
            assertTrue(json.contains("\"unverified_lookups\""));

            gc.writeMetrics();
            assertTrue(Files.exists(Paths.get("logs/metrics.json")));
            gc.assertNoLeaks();
        } finally {
            gc.close();
        }
    }
}
