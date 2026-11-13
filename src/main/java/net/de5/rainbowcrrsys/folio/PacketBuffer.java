package net.de5.rainbowcrrsys.folio;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * A small growable byte cursor used for packet encoding and frame accumulation.
 *
 * <p>Instances are pooled by {@link GcRuntime}. The recycler calls {@link #reset()} so a
 * borrowed buffer never carries state into the next use. A lease and its buffer stay on the
 * borrowing selector thread for their whole lifetime (gc-primitives 0.2.0 Pool contract).
 */
public final class PacketBuffer {

    private static final int INITIAL_CAPACITY = 256;
    private static final int MAX_CAPACITY = 2 * 1024 * 1024 + 5;

    private byte[] data;
    private int readerIndex;
    private int writerIndex;

    public PacketBuffer() {
        this.data = new byte[INITIAL_CAPACITY];
    }

    private PacketBuffer(byte[] initial) {
        this.data = initial;
        this.writerIndex = initial.length;
    }

    public static PacketBuffer wrap(byte[] bytes) {
        return new PacketBuffer(Arrays.copyOf(bytes, bytes.length));
    }

    public void reset() {
        readerIndex = 0;
        writerIndex = 0;
    }

    public int readerIndex() {
        return readerIndex;
    }

    public void readerIndex(int index) {
        if (index < 0 || index > writerIndex) throw new IndexOutOfBoundsException("readerIndex");
        readerIndex = index;
    }

    public int size() {
        return writerIndex - readerIndex;
    }

    public int readableBytes() {
        return writerIndex - readerIndex;
    }

    /** Drops consumed bytes and keeps an incomplete frame at the front of the buffer. */
    public void compact() {
        int remaining = readableBytes();
        if (remaining > 0 && readerIndex > 0) {
            System.arraycopy(data, readerIndex, data, 0, remaining);
        }
        readerIndex = 0;
        writerIndex = remaining;
    }

    public byte[] toByteArray() {
        return Arrays.copyOfRange(data, readerIndex, writerIndex);
    }

    public byte[] array() {
        return data;
    }

    public int capacity() {
        return data.length;
    }

    private void ensureCapacity(int extra) {
        int needed = writerIndex + extra;
        if (needed <= data.length) return;
        if (needed > MAX_CAPACITY) {
            throw new IllegalStateException("packet buffer exceeds 2 MiB frame limit: " + needed);
        }
        int next = Math.max(needed, Math.min(MAX_CAPACITY, data.length * 2));
        data = Arrays.copyOf(data, next);
    }

    private void need(int count) {
        if (writerIndex - readerIndex < count) {
            throw new IndexOutOfBoundsException("buffer underflow, need " + count);
        }
    }

    public void writeByte(int value) {
        ensureCapacity(1);
        data[writerIndex++] = (byte) value;
    }

    public int readByte() {
        need(1);
        return data[readerIndex++] & 0xFF;
    }

    public void writeBytes(byte[] src) {
        writeBytes(src, 0, src.length);
    }

    public void writeBytes(byte[] src, int offset, int length) {
        ensureCapacity(length);
        System.arraycopy(src, offset, data, writerIndex, length);
        writerIndex += length;
    }

    public byte[] readBytes(int length) {
        if (length < 0) throw new IllegalArgumentException("negative length");
        need(length);
        byte[] out = Arrays.copyOfRange(data, readerIndex, readerIndex + length);
        readerIndex += length;
        return out;
    }

    public void writeVarInt(int value) {
        int v = value;
        do {
            int b = v & 0x7F;
            v >>>= 7;
            if (v != 0) b |= 0x80;
            writeByte(b);
        } while (v != 0);
    }

    public int readVarInt() {
        int value = 0;
        int bits = 0;
        for (int i = 0; i < 5; i++) {
            int b = readByte();
            value |= (b & 0x7F) << bits;
            if ((b & 0x80) == 0) return value;
            bits += 7;
        }
        throw new IllegalArgumentException("VarInt is too long");
    }

    public void writeLong(long value) {
        for (int i = 7; i >= 0; i--) writeByte((int) (value >>> (i * 8)) & 0xFF);
    }

    public long readLong() {
        long value = 0L;
        for (int i = 0; i < 8; i++) value = (value << 8) | readByte();
        return value;
    }

    public void writeInt(int value) {
        writeByte((value >>> 24) & 0xFF);
        writeByte((value >>> 16) & 0xFF);
        writeByte((value >>> 8) & 0xFF);
        writeByte(value & 0xFF);
    }

    public int readInt() {
        return (readByte() << 24) | (readByte() << 16) | (readByte() << 8) | readByte();
    }

    public void writeFloat(float value) {
        writeInt(Float.floatToIntBits(value));
    }

    public float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    public void writeDouble(double value) {
        writeLong(Double.doubleToLongBits(value));
    }

    public double readDouble() {
        return Double.longBitsToDouble(readLong());
    }

    public void writeUnsignedShort(int value) {
        writeByte((value >>> 8) & 0xFF);
        writeByte(value & 0xFF);
    }

    public int readUnsignedShort() {
        return (readByte() << 8) | readByte();
    }

    public void writeBoolean(boolean value) {
        writeByte(value ? 1 : 0);
    }

    public boolean readBoolean() {
        return readByte() != 0;
    }

    public void writeUuid(UUID uuid) {
        writeLong(uuid.getMostSignificantBits());
        writeLong(uuid.getLeastSignificantBits());
    }

    public UUID readUuid() {
        long msb = readLong();
        long lsb = readLong();
        return new UUID(msb, lsb);
    }

    public void writeString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length);
        writeBytes(bytes);
    }

    public String readString(int maxChars) {
        int length = readVarInt();
        if (length < 0 || length > maxChars * 3 + 3) {
            throw new IllegalArgumentException("string too long: " + length);
        }
        return new String(readBytes(length), StandardCharsets.UTF_8);
    }

    public void writeByteArray(byte[] bytes) {
        writeVarInt(bytes.length);
        writeBytes(bytes);
    }

    public byte[] readByteArray(int maxBytes) {
        int length = readVarInt();
        if (length < 0 || length > maxBytes) {
            throw new IllegalArgumentException("byte array too long: " + length);
        }
        return readBytes(length);
    }
}
