package com.kodari.notesongs;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SongPacket(byte[] data) implements CustomPayload {
    public static final Id<SongPacket> ID = new Id<>(Identifier.of("note_block_songs", "transfer"));
    public static final PacketCodec<RegistryByteBuf, SongPacket> CODEC =
            PacketCodec.tuple(PacketCodecs.BYTE_ARRAY, SongPacket::data, SongPacket::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}