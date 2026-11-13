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

    /** Minecraft's SHA-1 digest: server id + shared secret + encoded public key, signed hex. */
    public String loginHash(byte[] sharedSecret) throws GeneralSecurityException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update("".getBytes(StandardCharsets.US_ASCII));
        sha1.update(sharedSecret);
        sha1.update(publicKey);
        BigInteger value = new BigInteger(sha1.digest());
        String hex = value.toString(16);
        return hex.startsWith("-") ? hex.substring(1) : hex;
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

    /** GET &lt;session-url&gt;?username=&lt;name&gt;&amp;serverId=&lt;hash&gt; */
    public UUID verifySession(String name, String hash) throws IOException {
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
                return profileUuid(name);
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("session server returned HTTP " + code);
            }
            String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = ID_PATTERN.matcher(body);
            if (!matcher.find()) throw new IOException("session response has no profile id");
            return fromPlainUuid(matcher.group(1));
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
