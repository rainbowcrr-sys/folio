package net.de5.rainbowcrrsys.folio;

import net.de5.rainbowcrrsys.gcprimitives.Lease;
import net.de5.rainbowcrrsys.gcprimitives.Pool;
import net.de5.rainbowcrrsys.gcprimitives.Pools;
import net.de5.rainbowcrrsys.gcprimitives.Recycler;
import net.de5.rainbowcrrsys.gcprimitives.V020;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full integration of gc-primitives 0.2.0.
 *
 * <p>{@link Pools#threadLocal} provides the per-selector frame buffer pool. Because its
 * state is thread-local, one shared {@code GcRuntime} instance safely gives every selector
 * thread its own bounded idle stack, and leases never cross a thread boundary.
 *
 * <p>The V020 increment layer is wired end to end: managed pools, YAML config loader,
 * PoolManager hot reload, the 16-field metrics snapshot, leak sampling, lifecycle hooks,
 * prewarm, memory scopes, and the frozen combination matrix (ShardedPool / TwoLevelPool).
 */
public final class GcRuntime implements AutoCloseable {

    private static final Recycler<PacketBuffer> RESET = PacketBuffer::reset;

    private final Pool<PacketBuffer> framePool;
    private final V020.PoolManager manager;
    private final V020.ManagedPool<PacketBuffer> frames;
    private final V020.ManagedPool<PacketBuffer> messages;
    private final V020.ShardedPool<PacketBuffer> sharded;
    private final V020.TwoLevelPool<PacketBuffer> twoLevel;
    private final V020.MetricsCollector collector;
    private final ScheduledExecutorService metricsWriter;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private GcRuntime(ServerConfig cfg) {
        this.framePool = Pools.<PacketBuffer>threadLocal(PacketBuffer::new, RESET, 64);

        V020.PoolConfig framesCfg = V020.loadConfig(cfg.path().toString(), "frames");
        V020.PoolConfig messagesCfg = V020.loadConfig(cfg.path().toString(), "messages");

        V020.LifecycleHooks<PacketBuffer> hooks = new V020.LifecycleHooks<>();
        hooks.onCreate = b -> { };
        hooks.onBorrow = PacketBuffer::reset;
        hooks.onReturn = PacketBuffer::reset;
        hooks.onEvict = b -> { };
        hooks.onDestroy = b -> { };
        hooks.clearSensitive = true;

        this.frames = new V020.ManagedPool<>(PacketBuffer::new, PacketBuffer::reset, framesCfg, hooks);
        this.messages = new V020.ManagedPool<>(PacketBuffer::new, PacketBuffer::reset, messagesCfg, hooks);

        V020.assertCombination(V020.PoolKind.BASE, "shardable");
        V020.assertCombination(V020.PoolKind.BASE, "two_level_l2");

        List<V020.ManagedPool<PacketBuffer>> shards = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            shards.add(new V020.ManagedPool<>(PacketBuffer::new, PacketBuffer::reset, framesCfg, hooks));
        }
        this.sharded = new V020.ShardedPool<>(shards, V020.PoolKind.BASE);
        V020.ManagedPool<PacketBuffer> l2 =
                new V020.ManagedPool<>(PacketBuffer::new, PacketBuffer::reset, framesCfg, hooks);
        this.twoLevel = new V020.TwoLevelPool<>(l2, 8, V020.PoolKind.BASE);

        this.collector = new V020.MetricsCollector();

        this.manager = new V020.PoolManager(cfg.path().toString());
        manager.register("frames", frames);
        manager.register("messages", messages);
        manager.enableHotReload(1000L);

        this.metricsWriter = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "folio-metrics");
            t.setDaemon(true);
            return t;
        });
        metricsWriter.scheduleAtFixedRate(this::writeMetrics, 30L, 30L, TimeUnit.SECONDS);
    }

    public static GcRuntime start(ServerConfig cfg) {
        return new GcRuntime(cfg);
    }

    /** Thread-local borrow: the lease must be closed by the same selector thread. */
    public Lease<PacketBuffer> borrowFrame() {
        return framePool.borrow();
    }

    public V020.ManagedPool<PacketBuffer> frames() {
        return frames;
    }

    public V020.ManagedPool<PacketBuffer> messages() {
        return messages;
    }

    public V020.ShardedPool<PacketBuffer> sharded() {
        return sharded;
    }

    public V020.TwoLevelPool<PacketBuffer> twoLevel() {
        return twoLevel;
    }

    public V020.MetricsCollector collector() {
        return collector;
    }

    /** Diagnostic scope; a no-op unless GC_PRIMITIVES_DIAG is set. */
    public V020.MemoryScope scope(String name) {
        java.util.function.Consumer<V020.ScopeReport> sink = null;
        String jsonPath = null;
        return V020.memoryScope(name, sink, jsonPath, collector);
    }

    public void recordFrame(double waitMs, boolean pooledHit) {
        collector.recordBorrow(pooledHit, waitMs, !pooledHit);
        collector.recordReturn(waitMs);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("frames", frames.metrics());
        m.put("messages", messages.metrics());
        m.put("sharded", sharded.metrics());
        m.put("two_level", twoLevel.metrics());
        m.put("collector", collector.snapshot(0L, 0L, false));
        m.put("frames_snapshot", frames.snapshot());
        m.put("messages_snapshot", messages.snapshot());
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("capacity", frames.configMaxSize());
        m.put("unified_shape", V020.metricsSnapshot(overrides));
        m.put("unified_fields", V020.METRICS_FIELDS);
        m.put("unverified_lookups", new ArrayList<>(PacketIds.unverifiedLookups()));
        m.put("leaks_frames", frames.detectLeaks(-1L));
        m.put("leaks_messages", messages.detectLeaks(-1L));
        m.put("virtual_thread_note", V020.virtualThreadNote());
        m.put("diag_enabled", V020.diagEnabled());
        return m;
    }

    public void writeMetrics() {
        try {
            Path dir = Paths.get("logs");
            Files.createDirectories(dir);
            String json = V020.toJson(snapshot()) + "\n";
            Files.writeString(dir.resolve("metrics.json"), json,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            System.getLogger("folio.gc").log(System.Logger.Level.WARNING,
                    "metrics snapshot failed", e);
        }
    }

    public void assertNoLeaks() {
        framePool.assertNoActiveLeases();
        frames.assertNoActiveLeases();
        messages.assertNoActiveLeases();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        metricsWriter.shutdownNow();
        manager.close();
        sharded.close();
        twoLevel.close();
    }
}
