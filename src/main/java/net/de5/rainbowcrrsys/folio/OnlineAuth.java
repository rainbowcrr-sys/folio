package net.de5.rainbowcrrsys.folio;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Online mode, following the official protocol page.
 *
 * <p>A 1024-bit RSA keypair is generated in memory at startup and never written to disk.
 * The session URL is read from the live configuration on each request, so hot reload takes
 * effect for new connections. CI points it at a loopback mock session server.
 */
public final class OnlineAuth implements AuthProvider {

    private static final Pattern ID_PATTERN =
            Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F\\-]{32,36})\"");
    private static final Pattern NAME_PATTERN =
            Pattern.compile("\"name\"\\s*:\\s*\"([^\"]{1,16})\"");

    private final KeyPair keyPair;
    private final byte[] publicKey;
    private final ServerConfig cfg;
    private final SecureRandom random = new SecureRandom();

    public OnlineAuth(ServerConfig cfg) throws GeneralSecurityException {
        this.cfg = cfg;
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024, random);
        this.keyPair = generator.generateKeyPair();
        this.publicKey = keyPair.getPublic().getEncoded();
    }

    @Override
    public boolean isOnline() {
        return true;
    }

    @Override
    public UUID profileUuid(String name) {
        return UUID.nameUUIDFromBytes(("OnlinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public byte[] publicKey() {
        return publicKey.clone();
    }

    public byte[] newVerifyToken() {
        byte[] token = new byte[4];
        random.nextBytes(token);
        return token;
    }

    public byte[] decrypt(byte[] encrypted) throws GeneralSecurityException {
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
        return rsa.doFinal(encrypted);
    }

    /**
     * Minecraft's non-standard "hex digest": SHA-1 bytes interpreted as a signed big-endian
     * two's-complement integer, printed in base 16 with a leading {@code -} when negative.
     * This is exactly Java's {@code new BigInteger(digest).toString(16)} — the minus sign
     * is part of the serverId and must be kept. Stripping it makes roughly half of all
     * genuine logins fail with "Failed to verify username".
     *
     * <p>Published test vectors: sha1("Notch") = 4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48,
     * sha1("jeb_") = -7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1,
     * sha1("simon") = 88e16a1019277b15d58faf0541e11910eb756f6.
     */
    public static String minecraftHexDigest(byte[] digest) {
        return new BigInteger(digest).toString(16);
    }

    /** SHA-1 over server id (empty) + shared secret + X.509 public key, as a signed hex string. */
    public String loginHash(byte[] sharedSecret) throws GeneralSecurityException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update("".getBytes(StandardCharsets.ISO_8859_1));
        sha1.update(sharedSecret);
        sha1.update(publicKey);
        return minecraftHexDigest(sha1.digest());
    }

    public Cipher[] ciphers(byte[] sharedSecret) throws GeneralSecurityException {
        SecretKeySpec key = new SecretKeySpec(sharedSecret, "AES");
        IvParameterSpec iv = new IvParameterSpec(sharedSecret);
        Cipher encrypt = Cipher.getInstance("AES/CFB8/NoPadding");
        encrypt.init(Cipher.ENCRYPT_MODE, key, iv);
        Cipher decrypt = Cipher.getInstance("AES/CFB8/NoPadding");
        decrypt.init(Cipher.DECRYPT_MODE, key, iv);
        return new Cipher[]{encrypt, decrypt};
    }

    /** A verified Mojang profile: the UUID and the canonical name both come from hasJoined. */
    public static final class SessionProfile {
        private final UUID uuid;
        private final String name;

        SessionProfile(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        public UUID uuid() {
            return uuid;
        }

        public String name() {
            return name;
        }
    }

    /**
     * GET &lt;session-url&gt;?username=&lt;name&gt;&amp;serverId=&lt;hash&gt;
     *
     * <p>Only HTTP 200 with a profile is a pass. HTTP 204 means the player never called
     * {@code /join} (or the session is stale / IP-mismatched) and authentication must be
     * refused — treating 204 as success would let any unauthenticated client into an
     * online-mode server. This is the vanilla "Failed to verify username!" path.
     */
    public SessionProfile verifySession(String name, String hash) throws IOException {
        String base = cfg.sessionUrl;
        String separator = base.contains("?") ? "&" : "?";
        String query = "username=" + URLEncoder.encode(name, StandardCharsets.UTF_8)
                + "&serverId=" + URLEncoder.encode(hash, StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(base + separator + query).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        try {
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NO_CONTENT) {
                throw new IOException("Failed to verify username!");
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("session server returned HTTP " + code);
            }
            String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Matcher id = ID_PATTERN.matcher(body);
            if (!id.find()) throw new IOException("session response has no profile id");
            Matcher profileName = NAME_PATTERN.matcher(body);
            String canonical = profileName.find() ? profileName.group(1) : name;
            if (!canonical.equalsIgnoreCase(name)) {
                throw new IOException("session profile name does not match login name");
            }
            return new SessionProfile(fromPlainUuid(id.group(1)), canonical);
        } finally {
            connection.disconnect();
        }
    }

    private static UUID fromPlainUuid(String raw) {
        String hex = raw.replace("-", "");
        if (hex.length() != 32) throw new IllegalArgumentException("bad uuid: " + raw);
        BigInteger high = new BigInteger(hex.substring(0, 16), 16);
        BigInteger low = new BigInteger(hex.substring(16), 16);
        return new UUID(high.longValue(), low.longValue());
    }
}
