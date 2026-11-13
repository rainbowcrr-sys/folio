package net.de5.rainbowcrrsys.folio;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline verification of the server half of the authentication chain.
 *
 * <p>These tests need no Minecraft client, no paid account, no computer with a launcher,
 * and no network access. The digest vectors are the ones published on the official protocol
 * encryption page; the session-service behaviours follow the same page's documented
 * 200 / 204 semantics.
 */
class AuthVerificationTest {

    @Test
    void officialMinecraftDigestVectors() throws Exception {
        assertEquals("4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48", digest("Notch"));
        assertEquals("-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1", digest("jeb_"));
        assertEquals("88e16a1019277b15d58faf0541e11910eb756f6", digest("simon"));
    }

    @Test
    void negativeDigestKeepsItsMinusSign() throws Exception {
        String hex = digest("jeb_");
        assertTrue(hex.startsWith("-"), "the minus sign is part of the serverId");
        assertFalse(hex.isEmpty());
        assertTrue(hex.length() == 41, "a negative Minecraft digest is 41 characters");
    }

    @Test
    void loginHashUsesTheSameSignedDigest() throws Exception {
        Path dir = Files.createTempDirectory("folio-hash-");
        Path cfgFile = dir.resolve("config.yaml");
        Files.writeString(cfgFile, config("http://127.0.0.1:1/x"));
        OnlineAuth auth = new OnlineAuth(ServerConfig.fromPath(cfgFile));

        byte[] secret = new byte[16];
        new SecureRandom().nextBytes(secret);
        String hash = auth.loginHash(secret);

        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update("".getBytes(StandardCharsets.ISO_8859_1));
        sha1.update(secret);
        sha1.update(auth.publicKey());
        assertEquals(OnlineAuth.minecraftHexDigest(sha1.digest()), hash);
        // A minus sign is valid output; it must never be stripped.
        assertTrue(hash.matches("-?[0-9a-f]{1,40}"), "unexpected digest: " + hash);
    }

    @Test
    void rsaAndAesCfb8RoundTripAcrossConsecutiveFrames() throws Exception {
        Path dir = Files.createTempDirectory("folio-crypto-");
        Path cfgFile = dir.resolve("config.yaml");
        Files.writeString(cfgFile, config("http://127.0.0.1:1/x"));
        OnlineAuth auth = new OnlineAuth(ServerConfig.fromPath(cfgFile));

        byte[] secret = new byte[16];
        new SecureRandom().nextBytes(secret);
        PublicKey key = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(auth.publicKey()));
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, key);
        byte[] token = auth.newVerifyToken();
        assertTrue(MessageDigest.isEqual(token, auth.decrypt(rsa.doFinal(token))));
        assertTrue(MessageDigest.isEqual(secret, auth.decrypt(rsa.doFinal(secret))));

        Cipher[] server = auth.ciphers(secret);
        Cipher[] client = TestClient.clientCiphers(secret);
        // The cipher is continuous: length prefix and packet id are encrypted too, and the
        // stream is not reset between frames.
        byte[] f1 = new byte[]{0x07, 0x00, 0x01, 0x02, 0x03};
        byte[] f2 = new byte[]{0x03, (byte) 0xFF, 0x32};
        byte[] e1 = server[0].update(f1);
        byte[] e2 = server[0].update(f2);
        byte[] joined = new byte[e1.length + e2.length];
        System.arraycopy(e1, 0, joined, 0, e1.length);
        System.arraycopy(e2, 0, joined, e1.length, e2.length);
        byte[] plain = client[1].update(joined);
        assertTrue(MessageDigest.isEqual(
                new byte[]{0x07, 0x00, 0x01, 0x02, 0x03, 0x03, (byte) 0xFF, 0x32}, plain));
    }

    @Test
    void http204IsRefusedNotAccepted() throws Exception {
        assertRefused(MockSessionServer.Mode.NO_CONTENT);
    }

    @Test
    void http403IsRefused() throws Exception {
        assertRefused(MockSessionServer.Mode.FORBIDDEN);
    }

    @Test
    void mismatchedProfileNameIsRefused() throws Exception {
        assertRefused(MockSessionServer.Mode.NAME_MISMATCH);
    }

    @Test
    void http200ReturnsTheCanonicalProfileName() throws Exception {
        Path dir = Files.createTempDirectory("folio-profile-");
        MockSessionServer mock = new MockSessionServer(MockSessionServer.Mode.OK);
        try {
            Path cfgFile = dir.resolve("config.yaml");
            Files.writeString(cfgFile, config(mock.url()));
            OnlineAuth auth = new OnlineAuth(ServerConfig.fromPath(cfgFile));
            OnlineAuth.SessionProfile profile = auth.verifySession("foliotest", "abc123");
            assertEquals("069a79f4-444e-9472-6a5b-efca90e38aaf5", profile.uuid().toString());
            assertEquals("FolioTest", profile.name());
            assertTrue(mock.lastQuery().contains("serverId=abc123"), "query: " + mock.lastQuery());
            assertTrue(mock.lastQuery().contains("username=foliotest"));
        } finally {
            mock.close();
        }
    }

    private void assertRefused(MockSessionServer.Mode mode) throws Exception {
        Path dir = Files.createTempDirectory("folio-refuse-");
        MockSessionServer mock = new MockSessionServer(mode);
        Path cfgFile = dir.resolve("config.yaml");
        Files.writeString(cfgFile, config(mock.url()));
        ServerConfig cfg = ServerConfig.fromPath(cfgFile);
        GcRuntime gc = GcRuntime.start(cfg);
        OnlineAuth online = new OnlineAuth(cfg);
        NioServer server = new NioServer(cfg, gc, online);
        server.start();
        ProtocolVersion v = ProtocolVersion.V26_3;
        TestClient client = new TestClient(server.port(), v);
        try {
            client.send(PacketIds.HS_S_INTENTION, b -> {
                b.writeVarInt(v.protocol());
                b.writeString("127.0.0.1");
                b.writeUnsignedShort(25565);
                b.writeVarInt(2);
            });
            client.send(PacketIds.LOGIN_S_HELLO, b -> {
                b.writeString("FolioTest");
                b.writeUuid(UUID.randomUUID());
            });
            TestClient.Frame hello = client.readFrame();
            hello.body.readString(20);
            byte[] pub = hello.body.readByteArray(4096);
            byte[] token = hello.body.readByteArray(4096);
            PublicKey key = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(pub));
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, key);
            byte[] secret = new byte[16];
            new SecureRandom().nextBytes(secret);
            byte[] encryptedSecret = rsa.doFinal(secret);
            byte[] encryptedToken = rsa.doFinal(token);
            client.send(PacketIds.LOGIN_S_KEY, b -> {
                b.writeByteArray(encryptedSecret);
                b.writeByteArray(encryptedToken);
            });
            TestClient.Frame frame = client.readFrame();
            assertEquals(PacketIds.id(v, PacketIds.LOGIN_C_DISCONNECT), frame.id);
            String reason = frame.body.readString(32767);
            assertTrue(reason.contains("Failed to verify username"), "reason: " + reason);
            assertFalse(client.isClosed(), "socket is closed only after the disconnect is flushed");
        } finally {
            client.close();
            server.close();
            server.await();
            gc.assertNoLeaks();
            gc.close();
            mock.close();
        }
    }

    private static String digest(String text) throws Exception {
        return OnlineAuth.minecraftHexDigest(
                MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.ISO_8859_1)));
    }

    private static String config(String url) {
        return "server:\n  host: 127.0.0.1\n  port: 0\n  motd: \"auth test\"\n"
                + "  online-mode: true\n  session-url: \"" + url + "\"\n"
                + "  max-frame-size: 2097152\n  max-connections: 256\n"
                + "  keep-alive-interval-ms: 15000\n  keep-alive-timeout-ms: 30000\n"
                + "  selector-threads: 1\n"
                + "pools:\n  frames:\n    max_size: 64\n    on_full: drop_oldest\n"
                + "    leak_detection: true\n    prewarm: 8\n"
                + "  messages:\n    max_size: 64\n    on_full: drop_oldest\n"
                + "    leak_detection: true\n    prewarm: 8\n";
    }
}
