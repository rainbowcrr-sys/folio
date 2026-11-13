package net.de5.rainbowcrrsys.folio;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.function.Consumer;

/** Scripted protocol client used by the integration tests. Not a vanilla client. */
final class TestClient implements AutoCloseable {

    static final class Frame {
        final int id;
        final PacketBuffer body;

        Frame(int id, PacketBuffer body) {
            this.id = id;
            this.body = body;
        }
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final ProtocolVersion version;
    private Cipher enc;
    private Cipher dec;

    TestClient(int port, ProtocolVersion version) throws IOException {
        this.socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        socket.setSoTimeout(5000);
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        this.version = version;
    }

    void enableEncryption(Cipher encrypt, Cipher decrypt) {
        this.enc = encrypt;
        this.dec = decrypt;
    }

    static Cipher[] clientCiphers(byte[] sharedSecret) throws GeneralSecurityException {
        SecretKeySpec key = new SecretKeySpec(sharedSecret, "AES");
        IvParameterSpec iv = new IvParameterSpec(sharedSecret);
        Cipher encrypt = Cipher.getInstance("AES/CFB8/NoPadding");
        encrypt.init(Cipher.ENCRYPT_MODE, key, iv);
        Cipher decrypt = Cipher.getInstance("AES/CFB8/NoPadding");
        decrypt.init(Cipher.DECRYPT_MODE, key, iv);
        return new Cipher[]{encrypt, decrypt};
    }

    void send(String key, Consumer<PacketBuffer> body) throws IOException {
        PacketBuffer payload = new PacketBuffer();
        body.accept(payload);
        int id = PacketIds.id(version, key);
        PacketBuffer frame = new PacketBuffer();
        frame.writeVarInt(PacketIds.varIntSize(id) + payload.size());
        frame.writeVarInt(id);
        frame.writeBytes(payload.toByteArray());
        byte[] wire = frame.toByteArray();
        if (enc != null) {
            byte[] encrypted = enc.update(wire);
            wire = encrypted == null ? new byte[0] : encrypted;
        }
        out.write(wire);
        out.flush();
    }

    /** Reads one frame, decrypting byte by byte so the cipher switch is exact. */
    Frame readFrame() throws IOException {
        PacketBuffer buf = new PacketBuffer();
        while (true) {
            int one = in.read();
            if (one < 0) throw new EOFException("server closed the connection");
            byte[] raw = new byte[]{(byte) one};
            byte[] plain = raw;
            if (dec != null) {
                byte[] decoded = dec.update(raw);
                plain = decoded == null ? new byte[0] : decoded;
            }
            if (plain.length > 0) buf.writeBytes(plain);
            int mark = buf.readerIndex();
            if (buf.readableBytes() == 0) continue;
            int length;
            try {
                length = buf.readVarInt();
            } catch (IndexOutOfBoundsException underflow) {
                buf.readerIndex(mark);
                continue;
            }
            if (buf.readableBytes() < length) {
                buf.readerIndex(mark);
                continue;
            }
            byte[] payload = buf.readBytes(length);
            PacketBuffer packet = PacketBuffer.wrap(payload);
            return new Frame(packet.readVarInt(), packet);
        }
    }

    boolean isClosed() {
        return socket.isClosed();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing during teardown is best effort.
        }
    }
}
