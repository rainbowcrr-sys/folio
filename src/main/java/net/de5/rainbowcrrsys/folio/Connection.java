package net.de5.rainbowcrrsys.folio;

import net.de5.rainbowcrrsys.gcprimitives.Lease;
import net.de5.rainbowcrrsys.gcprimitives.V020;

import javax.crypto.Cipher;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * One TCP connection and its protocol state machine.
 *
 * <p>All work happens on the owning selector thread. Pooled buffers are borrowed, used and
 * returned on that thread only, which is the gc-primitives 0.2.0 Pool contract.
 *
 * <p>Configuration now performs the full vanilla negotiation: Known Packs round trip,
 * Feature Flags, Registry Data, Update Tags, then Finish Configuration. Play sends the
 * complete join sequence from {@link PlaySequence}.
 */
final class Connection {

    enum ConnState { HANDSHAKING, STATUS, LOGIN, CONFIGURATION, PLAY, CLOSED }

    private final SocketChannel channel;
    private final ServerConfig cfg;
    private final OnlineAuth online;
    private final AuthProvider auth;
    private final GcRuntime gc;
    private final Lease<PacketBuffer> rawLease;
    private final PacketBuffer rawBuf;
    private final Lease<PacketBuffer> inLease;
    private final PacketBuffer inBuf;
    private final ArrayDeque<byte[]> outQueue = new ArrayDeque<>();

    private ConnState state = ConnState.HANDSHAKING;
    private ProtocolVersion version = ProtocolVersion.V26_3;
    private String name;
    private UUID uuid;
    private UUID sessionId;
    private byte[] verifyToken;
    private Cipher encryptCipher;
    private Cipher decryptCipher;
    private boolean knownPacksSent;
    private boolean registrySent;

    private byte[] partialOut;
    private boolean closeAfterFlush;
    private boolean leasesReleased;

    private long keepAliveId;
    private boolean keepAlivePending;
    private long lastKeepAliveSentAt;
    private long lastKeepAliveResponseAt;
    private boolean keepAliveStarted;

    Connection(SocketChannel channel, ServerConfig cfg, OnlineAuth online, GcRuntime gc) {
        this.channel = channel;
        this.cfg = cfg;
        this.online = online;
        this.auth = cfg.onlineMode ? online : new OfflineAuth();
        this.gc = gc;
        this.rawLease = gc.borrowFrame();
        this.rawBuf = rawLease.get();
        this.rawBuf.reset();
        this.inLease = gc.borrowFrame();
        this.inBuf = inLease.get();
        this.inBuf.reset();
        this.sessionId = UUID.randomUUID();
    }

    AuthProvider auth() {
        return auth;
    }

    ProtocolVersion version() {
        return version;
    }

    ConnState state() {
        return state;
    }

    boolean isClosed() {
        return state == ConnState.CLOSED || closeAfterFlush;
    }

    boolean needsWrite() {
        return partialOut != null || !outQueue.isEmpty();
    }

    boolean shouldCloseAfterFlush() {
        return closeAfterFlush && partialOut == null && outQueue.isEmpty();
    }

    /** Raw TCP bytes in; decrypt byte by byte so the cipher switch is exact. */
    void onRead(byte[] raw) throws IOException {
        rawBuf.writeBytes(raw);
        while (rawBuf.readableBytes() > 0 && state != ConnState.CLOSED) {
            byte[] one = rawBuf.readBytes(1);
            byte[] plain = one;
            if (decryptCipher != null) {
                byte[] decoded = decryptCipher.update(one);
                plain = decoded == null ? new byte[0] : decoded;
            }
            if (plain.length > 0) inBuf.writeBytes(plain);
            parseFrames();
        }
        rawBuf.compact();
    }

    private void parseFrames() {
        while (state != ConnState.CLOSED && !closeAfterFlush) {
            int mark = inBuf.readerIndex();
            if (inBuf.readableBytes() == 0) break;
            int length;
            try {
                length = inBuf.readVarInt();
            } catch (IndexOutOfBoundsException underflow) {
                inBuf.readerIndex(mark);
                break;
            }
            if (length <= 0 || length > cfg.maxFrameSize) {
                disconnectNow("Frame length out of range");
                return;
            }
            if (inBuf.readableBytes() < length) {
                inBuf.readerIndex(mark);
                break;
            }
            byte[] payload = inBuf.readBytes(length);
            onPacket(payload);
        }
        inBuf.compact();
    }

    private void onPacket(byte[] payload) {
        PacketBuffer packet = PacketBuffer.wrap(payload);
        int id;
        try {
            id = packet.readVarInt();
        } catch (RuntimeException e) {
            disconnectNow("Malformed packet");
            return;
        }
        try (V020.MemoryScope ignored = gc.scope(state.name().toLowerCase())) {
            switch (state) {
                case HANDSHAKING: onHandshake(id, packet); break;
                case STATUS: onStatus(id, packet); break;
                case LOGIN: onLogin(id, packet); break;
                case CONFIGURATION: onConfiguration(id, packet); break;
                case PLAY: onPlay(id, packet); break;
                default: break;
            }
        } catch (RuntimeException e) {
            disconnectNow("Protocol error: " + e.getClass().getSimpleName());
        }
    }

    private void onHandshake(int id, PacketBuffer p) {
        if (id != PacketIds.id(version, PacketIds.HS_S_INTENTION)) return;
        int protocol = p.readVarInt();
        p.readString(255);
        p.readUnsignedShort();
        int intent = p.readVarInt();
        if (protocol < ProtocolVersion.MINIMUM
                || ProtocolVersion.fromProtocol(protocol).isEmpty()) {
            // Handshaking has no clientbound packet; switch to Login so a disconnect exists.
            state = ConnState.LOGIN;
            send(PacketIds.LOGIN_C_DISCONNECT,
                    b -> b.writeString(TextComponent.versionGate()));
            closeAfterFlush = true;
            return;
        }
        version = ProtocolVersion.fromProtocol(protocol).get();
        if (intent == 1) state = ConnState.STATUS;
        else if (intent == 2) state = ConnState.LOGIN;
        else disconnectNow("Unsupported handshake intent");
    }

    private void onStatus(int id, PacketBuffer p) {
        if (id == PacketIds.id(version, PacketIds.STATUS_S_REQUEST)) {
            send(PacketIds.STATUS_C_RESPONSE, this::writeStatusResponse);
        } else if (id == PacketIds.id(version, PacketIds.STATUS_S_PING)) {
            long timestamp = p.readLong();
            send(PacketIds.STATUS_C_PONG, b -> b.writeLong(timestamp));
        }
    }

    private void writeStatusResponse(PacketBuffer b) {
        String json = "{\"version\":{\"name\":\"" + version.display()
                + "\",\"protocol\":" + version.protocol() + "},"
                + "\"players\":{\"max\":" + cfg.maxConnections + ",\"online\":0},"
                + "\"description\":" + TextComponent.plain(cfg.motd) + "}";
        b.writeString(json);
    }

    private void onLogin(int id, PacketBuffer p) {
        if (id == PacketIds.id(version, PacketIds.LOGIN_S_HELLO)) {
            name = p.readString(16);
            try {
                uuid = p.readUuid();
            } catch (RuntimeException ignored) {
                // Older Login Start layouts omit the UUID.
            }
            if (!auth.isOnline()) {
                uuid = auth.profileUuid(name);
                sendLoginFinished();
            } else {
                verifyToken = online.newVerifyToken();
                byte[] pub = online.publicKey();
                byte[] token = verifyToken;
                send(PacketIds.LOGIN_C_HELLO, b -> {
                    b.writeString("");
                    b.writeByteArray(pub);
                    b.writeByteArray(token);
                    b.writeBoolean(true);
                });
            }
        } else if (id == PacketIds.id(version, PacketIds.LOGIN_S_KEY)) {
            if (!auth.isOnline()) {
                disconnectNow("Unexpected Encryption Response in offline mode");
                return;
            }
            byte[] encryptedSecret = p.readByteArray(512);
            byte[] encryptedToken = p.readByteArray(512);
            try {
                byte[] secret = online.decrypt(encryptedSecret);
                byte[] token = online.decrypt(encryptedToken);
                if (!MessageDigest.isEqual(token, verifyToken)) {
                    disconnectNow("Verify token mismatch");
                    return;
                }
                String hash = online.loginHash(secret);
                OnlineAuth.SessionProfile profile = online.verifySession(name, hash);
                uuid = profile.uuid();
                name = profile.name();
                Cipher[] ciphers = online.ciphers(secret);
                encryptCipher = ciphers[0];
                decryptCipher = ciphers[1];
                sendLoginFinished();
            } catch (Exception e) {
                disconnectNow("Failed to verify username!");
            }
        } else if (id == PacketIds.id(version, PacketIds.LOGIN_S_ACK)) {
            state = ConnState.CONFIGURATION;
            knownPacksSent = false;
            registrySent = false;
            send(PacketIds.CONFIG_C_KNOWN_PACKS,
                    b -> Registries.writeKnownPacks(b, version));
            knownPacksSent = true;
        }
    }

    private void sendLoginFinished() {
        UUID profile = uuid;
        String player = name;
        UUID session = sessionId;
        send(PacketIds.LOGIN_C_FINISHED, b -> {
            b.writeUuid(profile);
            b.writeString(player);
            b.writeVarInt(0);
            b.writeUuid(session);
        });
    }

    private void onConfiguration(int id, PacketBuffer p) {
        if (id == PacketIds.id(version, PacketIds.CONFIG_S_KNOWN_PACKS)) {
            if (!knownPacksSent || registrySent) return;
            drainKnownPacks(p);
            registrySent = true;
            send(PacketIds.CONFIG_C_FEATURE_FLAGS, Registries::writeFeatureFlags);
            for (Registries.Registry registry : Registries.load()) {
                Registries.Registry current = registry;
                send(PacketIds.CONFIG_C_REGISTRY_DATA,
                        b -> Registries.writeRegistryData(b, current));
            }
            send(PacketIds.CONFIG_C_UPDATE_TAGS, Registries::writeUpdateTags);
            send(PacketIds.CONFIG_C_FINISH, b -> { });
        } else if (id == PacketIds.id(version, PacketIds.CONFIG_S_CLIENT_INFO)) {
            // Ignored in this milestone.
        } else if (id == PacketIds.id(version, PacketIds.CONFIG_S_KEEP_ALIVE)) {
            onKeepAliveResponse(p.readLong());
        } else if (id == PacketIds.id(version, PacketIds.CONFIG_S_FINISH)) {
            enterPlay();
        }
    }

    private void drainKnownPacks(PacketBuffer p) {
        try {
            int count = p.readVarInt();
            for (int i = 0; i < Math.min(count, 64); i++) {
                p.readString(32767);
                p.readString(32767);
                p.readString(32767);
            }
        } catch (RuntimeException ignored) {
            // A minimal or empty response still allows the core to proceed.
        }
    }

    private void enterPlay() {
        state = ConnState.PLAY;
        keepAliveStarted = false;
        BiConsumer<String, Consumer<PacketBuffer>> sender = this::send;
        PlaySequence.emit(version, cfg, name, uuid, sender);
    }

    private void onPlay(int id, PacketBuffer p) {
        if (id == PacketIds.id(version, PacketIds.PLAY_S_KEEP_ALIVE)) {
            onKeepAliveResponse(p.readLong());
        } else if (id == PacketIds.id(version, PacketIds.PLAY_S_ACCEPT_TELEPORTATION)) {
            // 26.3 echoes the resulting position; no movement simulation here.
        } else if (id == PacketIds.id(version, PacketIds.PLAY_S_PLAYER_LOADED)) {
            // The loading screen closed; still no gameplay to start.
        } else if (id == PacketIds.id(version, PacketIds.PLAY_S_CHUNK_BATCH_RECEIVED)) {
            // Client-side chunk batch acknowledgement.
        }
        // Client Tick End, movement and other serverbound packets are intentionally ignored.
    }

    private void onKeepAliveResponse(long value) {
        if (keepAlivePending && value == keepAliveId) {
            keepAlivePending = false;
            lastKeepAliveResponseAt = System.currentTimeMillis();
        }
    }

    /** Driven by the selector loop; 15 s send / 30 s timeout by default. */
    void tick(long now) {
        if (state != ConnState.CONFIGURATION && state != ConnState.PLAY) return;
        if (!keepAliveStarted) {
            keepAliveStarted = true;
            lastKeepAliveSentAt = now;
            lastKeepAliveResponseAt = now;
            return;
        }
        if (now - lastKeepAliveSentAt >= cfg.keepAliveIntervalMs) {
            keepAliveId = ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE);
            keepAlivePending = true;
            lastKeepAliveSentAt = now;
            lastKeepAliveResponseAt = now;
            long id = keepAliveId;
            if (state == ConnState.PLAY) {
                send(PacketIds.PLAY_C_KEEP_ALIVE, b -> b.writeLong(id));
            } else {
                send(PacketIds.CONFIG_C_KEEP_ALIVE, b -> b.writeLong(id));
            }
        }
        if (keepAlivePending && now - lastKeepAliveResponseAt >= cfg.keepAliveTimeoutMs) {
            disconnectNow("Keep-Alive timeout");
        }
    }

    private void disconnectNow(String message) {
        if (state == ConnState.LOGIN) {
            send(PacketIds.LOGIN_C_DISCONNECT,
                    b -> b.writeString(TextComponent.plain(message)));
        } else if (state == ConnState.CONFIGURATION) {
            send(PacketIds.CONFIG_C_DISCONNECT, b -> Nbt.writeTextComponent(b, message));
        } else if (state == ConnState.PLAY) {
            send(PacketIds.PLAY_C_DISCONNECT, b -> Nbt.writeTextComponent(b, message));
        }
        closeAfterFlush = true;
    }

    private void send(String key, Consumer<PacketBuffer> body) {
        try (V020.ManagedLease<PacketBuffer> lease = gc.messages().borrow("outbound")) {
            PacketBuffer payload = lease.get();
            body.accept(payload);
            int id = PacketIds.id(version, key);
            int total = PacketIds.varIntSize(id) + payload.size();
            PacketBuffer frame = new PacketBuffer();
            frame.writeVarInt(total);
            frame.writeVarInt(id);
            frame.writeBytes(payload.toByteArray());
            enqueue(frame.toByteArray());
            gc.recordFrame(0.0, true);
        }
    }

    private void enqueue(byte[] frame) {
        byte[] wire = frame;
        if (encryptCipher != null) {
            byte[] encrypted = encryptCipher.update(frame);
            wire = encrypted == null ? new byte[0] : encrypted;
        }
        if (wire.length > 0) outQueue.add(wire);
    }

    /** Returns true when the whole outbound queue has been written. */
    boolean flush() throws IOException {
        if (partialOut != null) {
            ByteBuffer buffer = ByteBuffer.wrap(partialOut);
            int written = channel.write(buffer);
            if (buffer.hasRemaining()) {
                partialOut = Arrays.copyOfRange(partialOut, written, partialOut.length);
                return false;
            }
            partialOut = null;
        }
        while (!outQueue.isEmpty()) {
            byte[] wire = outQueue.poll();
            ByteBuffer buffer = ByteBuffer.wrap(wire);
            int written = channel.write(buffer);
            if (buffer.hasRemaining()) {
                partialOut = Arrays.copyOfRange(wire, written, wire.length);
                return false;
            }
        }
        return true;
    }

    /** Release both thread-local leases on the owning selector thread. */
    void cleanup() {
        if (leasesReleased) return;
        leasesReleased = true;
        state = ConnState.CLOSED;
        try {
            inLease.close();
        } finally {
            rawLease.close();
        }
    }
}
