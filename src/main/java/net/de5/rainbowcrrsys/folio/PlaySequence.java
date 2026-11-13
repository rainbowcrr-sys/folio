package net.de5.rainbowcrrsys.folio;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The Play-state join sequence a vanilla client needs before the loading screen can close.
 *
 * <p>Built from the official 26.3 protocol page: Login (play) with the complete
 * CommonPlayerSpawnInfo block, position sync, the mandatory "Start waiting for chunks"
 * Game Event, a chunk centre, an empty chunk, player-list/health/ability state, and keep-alive.
 * The world is a single all-air chunk — there is no terrain, entities, or gameplay.
 */
public final class PlaySequence {

    private static final int ENTITY_ID = 1;
    private static final int VIEW_DISTANCE = 8;
    private static final int SEA_LEVEL = 63;
    private static final int SECTION_COUNT = 24; // -64 .. 320, overworld height 384

    private PlaySequence() {
    }

    public static void emit(ProtocolVersion version, ServerConfig cfg, String name, UUID uuid,
                            BiConsumer<String, Consumer<PacketBuffer>> send) {
        send.accept(PacketIds.PLAY_C_LOGIN, b -> writeLogin(b, cfg));
        send.accept(PacketIds.PLAY_C_PLAYER_ABILITIES, b -> {
            b.writeByte(0x00);
            b.writeFloat(0.05f);
            b.writeFloat(0.1f);
        });
        send.accept(PacketIds.PLAY_C_SET_HELD_SLOT, b -> b.writeVarInt(0));
        send.accept(PacketIds.PLAY_C_PLAYER_INFO_UPDATE, b -> {
            b.writeByte(0x01); // ADD_PLAYER
            b.writeVarInt(1);
            b.writeUuid(uuid);
            b.writeString(name);
            b.writeVarInt(0); // no profile properties
        });
        send.accept(PacketIds.PLAY_C_SET_DEFAULT_SPAWN, b -> {
            b.writeString("minecraft:overworld");
            b.writeLong(position(8, 64, 8));
            b.writeFloat(0f);
            b.writeFloat(0f);
        });
        send.accept(PacketIds.PLAY_C_PLAYER_POSITION, b -> {
            b.writeVarInt(0); // teleport id
            b.writeDouble(8.0);
            b.writeDouble(64.0);
            b.writeDouble(8.0);
            b.writeDouble(0.0); // velocity x
            b.writeDouble(0.0); // velocity y
            b.writeDouble(0.0); // velocity z
            b.writeFloat(0f);   // yaw
            b.writeFloat(0f);   // pitch
            b.writeInt(0);      // teleport flags: all absolute
        });
        send.accept(PacketIds.PLAY_C_GAME_EVENT, b -> {
            b.writeByte(13); // Start waiting for level chunks
            b.writeFloat(0f);
        });
        send.accept(PacketIds.PLAY_C_SET_CENTER_CHUNK, b -> {
            b.writeVarInt(0);
            b.writeVarInt(0);
        });
        send.accept(PacketIds.PLAY_C_SET_RENDER_DISTANCE, b -> b.writeVarInt(VIEW_DISTANCE));
        send.accept(PacketIds.PLAY_C_SET_SIMULATION_DISTANCE, b -> b.writeVarInt(VIEW_DISTANCE));
        send.accept(PacketIds.PLAY_C_CHUNK_BATCH_START, b -> { });
        send.accept(PacketIds.PLAY_C_CHUNK_DATA, PlaySequence::writeEmptyChunk);
        send.accept(PacketIds.PLAY_C_CHUNK_BATCH_FINISHED, b -> b.writeVarInt(1));
        send.accept(PacketIds.PLAY_C_SET_HEALTH, b -> {
            b.writeFloat(20f);
            b.writeVarInt(20);
            b.writeFloat(5f);
        });
        send.accept(PacketIds.PLAY_C_SET_EXPERIENCE, b -> {
            b.writeFloat(0f);
            b.writeVarInt(0);
            b.writeVarInt(0);
        });
        send.accept(PacketIds.PLAY_C_SYSTEM_CHAT, b -> {
            Nbt.writeTextComponent(b, "Welcome to folio");
            b.writeBoolean(false); // chat, not action bar
        });
    }

    private static void writeLogin(PacketBuffer b, ServerConfig cfg) {
        b.writeInt(ENTITY_ID);
        b.writeBoolean(false); // hardcore
        b.writeVarInt(1);
        b.writeString("minecraft:overworld");
        b.writeVarInt(cfg.maxConnections);
        b.writeVarInt(VIEW_DISTANCE);
        b.writeVarInt(VIEW_DISTANCE);
        b.writeBoolean(false); // reduced debug info
        b.writeBoolean(true);  // enable respawn screen
        b.writeBoolean(false); // do limited crafting
        b.writeVarInt(0);      // dimension_type registry ID 0 = minecraft:overworld
        b.writeString("minecraft:overworld");
        b.writeLong(0L);       // hashed seed
        b.writeVarInt(0);      // survival
        b.writeVarInt(0);      // previous game mode undefined
        b.writeBoolean(false); // debug
        b.writeBoolean(false); // flat
        b.writeBoolean(false); // no last death location
        b.writeVarInt(0);      // portal cooldown
        b.writeVarInt(SEA_LEVEL);
        b.writeBoolean(cfg.onlineMode);
        b.writeBoolean(false); // enforces secure chat
    }

    /** One all-air chunk at (0,0) using single-value palettes. */
    private static void writeEmptyChunk(PacketBuffer b) {
        b.writeInt(0);
        b.writeInt(0);
        b.writeVarInt(0); // no heightmaps

        PacketBuffer sections = new PacketBuffer();
        for (int i = 0; i < SECTION_COUNT; i++) {
            sections.writeUnsignedShort(0); // non-air block count
            sections.writeByte(0);         // single-value palette, bits per entry = 0
            sections.writeVarInt(0);       // palette value: block state 0 (air)
            sections.writeVarInt(0);       // no packed data longs
            sections.writeByte(0);         // single-value biome palette
            sections.writeVarInt(0);       // biome ID 0
            sections.writeVarInt(0);       // no packed data longs
        }
        byte[] data = sections.toByteArray();
        b.writeVarInt(data.length);
        b.writeBytes(data);

        b.writeVarInt(0); // no block entities
        // Light Data: four empty BitSets followed by two empty arrays.
        b.writeVarInt(0);
        b.writeVarInt(0);
        b.writeVarInt(0);
        b.writeVarInt(0);
        b.writeVarInt(0);
        b.writeVarInt(0);
    }

    private static long position(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38)
                | ((long) (z & 0x3FFFFFF) << 12)
                | (y & 0xFFF);
    }
}
