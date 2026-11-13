package net.de5.rainbowcrrsys.folio;

import java.nio.charset.StandardCharsets;

/**
 * Minimal NBT writer for the 26.x wire format.
 *
 * <p>Since 26.x a Text Component is an NBT tag rather than a JSON string: a component that
 * contains only text is a TAG_String root. Registry entry data and chunk heightmaps are also
 * NBT. This writer covers exactly the tags this core needs; it is not a general NBT library.
 */
public final class Nbt {

    public static final int TAG_END = 0;
    public static final int TAG_BYTE = 1;
    public static final int TAG_SHORT = 2;
    public static final int TAG_INT = 3;
    public static final int TAG_LONG = 4;
    public static final int TAG_FLOAT = 5;
    public static final int TAG_DOUBLE = 6;
    public static final int TAG_BYTE_ARRAY = 7;
    public static final int TAG_STRING = 8;
    public static final int TAG_LIST = 9;
    public static final int TAG_COMPOUND = 10;
    public static final int TAG_INT_ARRAY = 11;
    public static final int TAG_LONG_ARRAY = 12;

    private Nbt() {
    }

    /** Root TAG_String with an empty name — a plain text component. */
    public static void writeTextComponent(PacketBuffer b, String text) {
        b.writeByte(TAG_STRING);
        b.writeUnsignedShort(0);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        b.writeUnsignedShort(bytes.length);
        b.writeBytes(bytes);
    }

    /** Empty compound, used as a placeholder for chat/session structures. */
    public static void writeEmptyCompound(PacketBuffer b) {
        b.writeByte(TAG_COMPOUND);
        b.writeUnsignedShort(0);
        b.writeByte(TAG_END);
    }

    public static void writeTagEnd(PacketBuffer b) {
        b.writeByte(TAG_END);
    }
}
