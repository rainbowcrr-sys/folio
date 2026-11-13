package net.de5.rainbowcrrsys.folio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Cipher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Protocol-level acceptance: a scripted client walks all five states for every supported
 * drop, in both authentication modes, completes the Configuration registry negotiation,
 * receives the full Play join sequence, and survives a keep-alive window.
 *
 * <p>The client and server both read the same registered packet table. This verifies the
 * protocol core, not a vanilla client rendering a world; see docs/DESIGN.md.
 */
class ProtocolIntegrationTest {

    @ParameterizedTest(name = "{0} offline")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    void offline(String versionName) throws Exception {
        run(versionName, false);
    }

    @ParameterizedTest(name = "{0} online / mock session")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    void online(String versionName) throws Exception {
        run(versionName, true);
    }

    @Test
    void rejectsClientsBelow261() throws Exception {
        Path dir = Files.createTempDirectory("folio-gate-");
        Path config = dir.resolve("config.yaml");
        Files.writeString(config, configText(false, "http://127.0.0.1:1/hasJoined"));
        ServerConfig cfg = ServerConfig.fromPath(config);
        GcRuntime gc = GcRuntime.start(cfg);
        OnlineAuth online = new OnlineAuth(cfg);
        NioServer server = new NioServer(cfg, gc, online);
        server.start();
        TestClient client = new TestClient(server.port(), ProtocolVersion.V26_3);
        try {
            client.send(PacketIds.HS_S_INTENTION, b -> {
                b.writeVarInt(774);
                b.writeString("127.0.0.1");
                b.writeUnsignedShort(25565);
                b.writeVarInt(2);
            });
            TestClient.Frame frame = client.readFrame();
            assertEquals(PacketIds.id(ProtocolVersion.V26_3, PacketIds.LOGIN_C_DISCONNECT),
                    frame.id);
            String json = frame.body.readString(32767);
            assertTrue(json.contains("26.1"), "English gate line missing");
            assertTrue(json.contains("本服务器"), "Chinese gate line missing");
        } finally {
            client.close();
            server.close();
            server.await();
            gc.assertNoLeaks();
            gc.close();
        }
    }

    private void run(String versionName, boolean onlineMode) throws Exception {
        Path dir = Files.createTempDirectory("folio-run-");
        MockSessionServer mock = onlineMode ? new MockSessionServer() : null;
        try {
            Path config = dir.resolve("config.yaml");
            String sessionUrl = mock == null ? "http://127.0.0.1:1/hasJoined" : mock.url();
            Files.writeString(config, configText(onlineMode, sessionUrl));

            ServerConfig cfg = ServerConfig.fromPath(config);
            GcRuntime gc = GcRuntime.start(cfg);
            OnlineAuth online = new OnlineAuth(cfg);
            NioServer server = new NioServer(cfg, gc, online);
            server.start();
            ProtocolVersion version = ProtocolVersion.fromName(versionName);
            TestClient client = new TestClient(server.port(), version);
            try {
                drive(client, version, onlineMode);
            } finally {
                client.close();
                server.close();
                server.await();
                gc.assertNoLeaks();
                gc.close();
            }
        } finally {
            if (mock != null) mock.close();
        }
    }

    private void drive(TestClient client, ProtocolVersion version, boolean onlineMode)
            throws Exception {
        client.send(PacketIds.HS_S_INTENTION, b -> {
            b.writeVarInt(version.protocol());
            b.writeString("127.0.0.1");
            b.writeUnsignedShort(25565);
            b.writeVarInt(2);
        });
        client.send(PacketIds.LOGIN_S_HELLO, b -> {
            b.writeString("FolioTest");
            b.writeUuid(UUID.randomUUID());
        });

        if (onlineMode) {
            TestClient.Frame hello = client.readFrame();
            assertEquals(PacketIds.id(version, PacketIds.LOGIN_C_HELLO), hello.id);
            hello.body.readString(20);
            byte[] publicKey = hello.body.readByteArray(4096);
            byte[] verifyToken = hello.body.readByteArray(4096);
            assertTrue(hello.body.readBoolean(), "should authenticate");

            PublicKey key = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(publicKey));
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, key);
            byte[] sharedSecret = new byte[16];
            new SecureRandom().nextBytes(sharedSecret);
            byte[] encryptedSecret = rsa.doFinal(sharedSecret);
            byte[] encryptedToken = rsa.doFinal(verifyToken);
            client.send(PacketIds.LOGIN_S_KEY, b -> {
                b.writeByteArray(encryptedSecret);
                b.writeByteArray(encryptedToken);
            });
            Cipher[] ciphers = TestClient.clientCiphers(sharedSecret);
            client.enableEncryption(ciphers[0], ciphers[1]);
        }

        TestClient.Frame finished = client.readFrame();
        assertEquals(PacketIds.id(version, PacketIds.LOGIN_C_FINISHED), finished.id);

        // Configuration: Known Packs round trip, Registry Data, Update Tags, Finish.
        client.send(PacketIds.LOGIN_S_ACK, b -> { });
        int registryCount = 0;
        boolean knownPacksSeen = false;
        boolean featureFlagsSeen = false;
        boolean tagsSeen = false;
        while (true) {
            TestClient.Frame frame = client.readFrame();
            if (frame.id == PacketIds.id(version, PacketIds.CONFIG_C_KNOWN_PACKS)) {
                knownPacksSeen = true;
                client.send(PacketIds.CONFIG_S_KNOWN_PACKS, b -> {
                    b.writeVarInt(1);
                    b.writeString("minecraft");
                    b.writeString("core");
                    b.writeString(version.display());
                });
            } else if (frame.id == PacketIds.id(version, PacketIds.CONFIG_C_FEATURE_FLAGS)) {
                featureFlagsSeen = true;
            } else if (frame.id == PacketIds.id(version, PacketIds.CONFIG_C_REGISTRY_DATA)) {
                registryCount++;
            } else if (frame.id == PacketIds.id(version, PacketIds.CONFIG_C_UPDATE_TAGS)) {
                tagsSeen = true;
            } else if (frame.id == PacketIds.id(version, PacketIds.CONFIG_C_FINISH)) {
                break;
            } else if (frame.id == PacketIds.id(version, PacketIds.CONFIG_C_DISCONNECT)) {
                fail("server disconnected during configuration");
            }
        }
        assertTrue(knownPacksSeen, "Known Packs negotiation missing");
        assertTrue(featureFlagsSeen, "Feature Flags missing");
        assertTrue(tagsSeen, "Update Tags missing");
        assertTrue(registryCount >= 30, "expected the full synchronized registry set, got "
                + registryCount);

        // Play: full join sequence followed by keep-alives.
        client.send(PacketIds.CONFIG_S_FINISH, b -> { });
        boolean loginSeen = false;
        boolean positionSeen = false;
        boolean gameEventSeen = false;
        boolean chunkSeen = false;
        int keepAlives = 0;
        long deadline = System.currentTimeMillis() + 1400L;
        while (System.currentTimeMillis() < deadline) {
            TestClient.Frame frame = client.readFrame();
            if (frame.id == PacketIds.id(version, PacketIds.PLAY_C_DISCONNECT)) {
                fail("server disconnected the client during the keep-alive window");
            }
            if (frame.id == PacketIds.id(version, PacketIds.PLAY_C_LOGIN)) loginSeen = true;
            else if (frame.id == PacketIds.id(version, PacketIds.PLAY_C_PLAYER_POSITION)) positionSeen = true;
            else if (frame.id == PacketIds.id(version, PacketIds.PLAY_C_GAME_EVENT)) gameEventSeen = true;
            else if (frame.id == PacketIds.id(version, PacketIds.PLAY_C_CHUNK_DATA)) chunkSeen = true;
            else if (frame.id == PacketIds.id(version, PacketIds.PLAY_C_KEEP_ALIVE)) {
                long id = frame.body.readLong();
                client.send(PacketIds.PLAY_S_KEEP_ALIVE, b -> b.writeLong(id));
                keepAlives++;
            }
        }
        assertTrue(loginSeen, "Play Login missing");
        assertTrue(positionSeen, "Synchronize Player Position missing");
        assertTrue(gameEventSeen, "Start waiting for chunks Game Event missing");
        assertTrue(chunkSeen, "empty chunk missing");
        assertTrue(keepAlives >= 2, "expected at least two keep-alives, got " + keepAlives);
        assertFalse(client.isClosed(), "client socket should still be open");
    }

    private static String configText(boolean onlineMode, String sessionUrl) {
        return "server:\n"
                + "  host: 127.0.0.1\n"
                + "  port: 0\n"
                + "  motd: \"A 26.x protocol core\"\n"
                + "  online-mode: " + onlineMode + "\n"
                + "  session-url: \"" + sessionUrl + "\"\n"
                + "  max-frame-size: 2097152\n"
                + "  max-connections: 256\n"
                + "  keep-alive-interval-ms: 300\n"
                + "  keep-alive-timeout-ms: 5000\n"
                + "  selector-threads: 1\n"
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
    }
}
