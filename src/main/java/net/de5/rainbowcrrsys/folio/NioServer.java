package net.de5.rainbowcrrsys.folio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Native NIO transport. One selector thread owns the whole hot path:
 * read bytes -> frame decode -> packet decode -> state machine -> encode -> write.
 *
 * <p>With more than one selector, connections are assigned by hash and each worker owns an
 * independent pool set. No pooled object ever crosses a thread boundary.
 */
public final class NioServer implements AutoCloseable {

    private final ServerConfig cfg;
    private final GcRuntime gc;
    private final OnlineAuth online;
    private final ServerSocketChannel serverChannel;
    private final List<Worker> workers = new ArrayList<>();
    private final AtomicInteger connections = new AtomicInteger();
    private volatile boolean running = true;

    public NioServer(ServerConfig cfg, GcRuntime gc, OnlineAuth online) throws IOException {
        this.cfg = cfg;
        this.gc = gc;
        this.online = online;
        this.serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(cfg.host, cfg.port));
        int count = Math.max(1, cfg.selectorThreads);
        for (int i = 0; i < count; i++) workers.add(new Worker(i));
        workers.get(0).registerServer(serverChannel);
    }

    public int port() throws IOException {
        return ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
    }

    public void start() {
        for (Worker worker : workers) worker.start();
    }

    public void await() throws InterruptedException {
        for (Worker worker : workers) worker.join();
    }

    @Override
    public void close() {
        running = false;
        for (Worker worker : workers) worker.wakeUp();
        try {
            serverChannel.close();
        } catch (IOException ignored) {
            // Closing during shutdown is best effort.
        }
    }

    private final class Worker implements Runnable {

        private final int index;
        private final Selector selector;
        private final Thread thread;
        private final Queue<SocketChannel> pending = new ConcurrentLinkedQueue<>();
        private final Map<SocketChannel, Connection> channels = new HashMap<>();
        private final ByteBuffer readBuffer = ByteBuffer.allocate(8192);

        Worker(int index) throws IOException {
            this.index = index;
            this.selector = Selector.open();
            this.thread = new Thread(this, "folio-selector-" + index);
        }

        void registerServer(ServerSocketChannel channel) throws IOException {
            channel.register(selector, SelectionKey.OP_ACCEPT);
        }

        void offer(SocketChannel channel) {
            pending.add(channel);
            selector.wakeup();
        }

        void start() {
            thread.start();
        }

        void join() throws InterruptedException {
            thread.join();
        }

        void wakeUp() {
            selector.wakeup();
        }

        @Override
        public void run() {
            try {
                while (running) {
                    drainPending();
                    selector.select(50);
                    if (index == 0) cfg.reloadIfChanged();
                    Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                    while (iterator.hasNext()) {
                        SelectionKey key = iterator.next();
                        iterator.remove();
                        if (!key.isValid()) continue;
                        try {
                            if (key.isAcceptable()) accept(key);
                            else if (key.isReadable()) read(key);
                            else if (key.isWritable()) write(key);
                        } catch (IOException e) {
                            cancel(key);
                        }
                    }
                    tick();
                }
            } catch (IOException e) {
                System.getLogger("folio.nio").log(System.Logger.Level.ERROR, "selector loop failed", e);
            } finally {
                for (Map.Entry<SocketChannel, Connection> entry
                        : new ArrayList<>(channels.entrySet())) {
                    release(entry.getKey(), entry.getValue());
                }
                try {
                    selector.close();
                } catch (IOException ignored) {
                    // Closing during shutdown is best effort.
                }
            }
        }

        private void drainPending() throws IOException {
            SocketChannel channel;
            while ((channel = pending.poll()) != null) {
                register(channel);
            }
        }

        private void register(SocketChannel channel) throws IOException {
            channel.configureBlocking(false);
            SelectionKey key = channel.register(selector, SelectionKey.OP_READ);
            Connection connection = new Connection(channel, cfg, online, gc);
            key.attach(connection);
            channels.put(channel, connection);
        }

        private void accept(SelectionKey key) throws IOException {
            ServerSocketChannel listener = (ServerSocketChannel) key.channel();
            SocketChannel channel;
            while ((channel = listener.accept()) != null) {
                if (connections.incrementAndGet() > cfg.maxConnections) {
                    connections.decrementAndGet();
                    channel.close();
                    continue;
                }
                Worker target = workers.get(Math.floorMod(channel.hashCode(), workers.size()));
                if (target == this) register(channel);
                else target.offer(channel);
            }
        }

        private void read(SelectionKey key) throws IOException {
            SocketChannel channel = (SocketChannel) key.channel();
            Connection connection = (Connection) key.attachment();
            if (connection == null) return;
            readBuffer.clear();
            int count = channel.read(readBuffer);
            if (count < 0) {
                release(channel, connection);
                return;
            }
            if (count == 0) return;
            readBuffer.flip();
            byte[] raw = new byte[readBuffer.remaining()];
            readBuffer.get(raw);
            connection.onRead(raw);
        }

        private void write(SelectionKey key) throws IOException {
            SocketChannel channel = (SocketChannel) key.channel();
            Connection connection = (Connection) key.attachment();
            if (connection == null) return;
            connection.flush();
        }

        private void tick() {
            long now = System.currentTimeMillis();
            for (Map.Entry<SocketChannel, Connection> entry : new ArrayList<>(channels.entrySet())) {
                SocketChannel channel = entry.getKey();
                Connection connection = entry.getValue();
                connection.tick(now);
                SelectionKey key = channel.keyFor(selector);
                if (key != null && key.isValid()) {
                    int ops = SelectionKey.OP_READ
                            | (connection.needsWrite() ? SelectionKey.OP_WRITE : 0);
                    if (key.interestOps() != ops) key.interestOps(ops);
                }
                if (connection.shouldCloseAfterFlush()) release(channel, connection);
            }
        }

        private void cancel(SelectionKey key) {
            Connection connection = (Connection) key.attachment();
            key.cancel();
            Object channel = key.channel();
            channels.remove(channel);
            if (connection != null) {
                connection.cleanup();
                connections.decrementAndGet();
            }
            if (channel instanceof SocketChannel) {
                try {
                    ((SocketChannel) channel).close();
                } catch (IOException ignored) {
                    // Closing during teardown is best effort.
                }
            }
        }

        private void release(SocketChannel channel, Connection connection) {
            SelectionKey key = channel.keyFor(selector);
            if (key != null) key.cancel();
            channels.remove(channel);
            connection.cleanup();
            try {
                channel.close();
            } catch (IOException ignored) {
                // Closing during shutdown is best effort.
            }
            connections.decrementAndGet();
        }
    }
}
