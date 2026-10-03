package com.kodari.notesongs;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class PlaybackServer {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int CHUNK_BYTES = 24 * 1024;
    private static final int MAX_ACTIVE_SONGS = 8;
    private static final double RANGE = 48.0;
    private static final Map<UUID, Upload> UPLOADS = new HashMap<>();
    private static final Map<UUID, Set<String>> CLIENT_CACHES = new HashMap<>();
    private static final Map<String, ActiveSong> ACTIVE = new HashMap<>();

    private PlaybackServer() {
    }

    static void handle(ServerPlayerEntity player, byte[] packet) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(packet))) {
            int action = in.readUnsignedByte();
            if (action == 1) {
                begin(player, in);
            } else if (action == 2) {
                receiveChunk(player, in);
            } else if (action == 3) {
                stopRequest(player, in);
            } else if (action == 4) {
                rememberCache(player, in);
            }
        } catch (Exception ignored) {
            UPLOADS.remove(player.getUuid());
        }
    }

    private static void begin(ServerPlayerEntity player, DataInputStream in) throws Exception {
        UUID id = new UUID(in.readLong(), in.readLong());
        BlockPos pos = new BlockPos(in.readInt(), in.readInt(), in.readInt());
        int length = in.readInt();
        int chunks = in.readInt();
        String hash = in.readUTF();
        String name = in.readUTF().replaceAll("[\\r\\n]", "");
        ServerWorld world = player.getServerWorld();
        if (length < 1 || length > MAX_BYTES || chunks != (length + CHUNK_BYTES - 1) / CHUNK_BYTES
                || !hash.matches("[0-9a-f]{64}") || !world.getBlockState(pos).isOf(Blocks.NOTE_BLOCK)
                || player.squaredDistanceTo(pos.toCenterPos()) > 64.0 || UPLOADS.size() >= 8) {
            return;
        }
        UPLOADS.put(player.getUuid(), new Upload(id, pos, length, chunks, hash,
                name.length() > 80 ? name.substring(0, 80) : name, new byte[length]));
    }

    private static void receiveChunk(ServerPlayerEntity player, DataInputStream in) throws Exception {
        Upload upload = UPLOADS.get(player.getUuid());
        UUID id = new UUID(in.readLong(), in.readLong());
        int index = in.readInt();
        int length = in.readInt();
        if (upload == null || !upload.id.equals(id) || index != upload.nextChunk
                || length < 1 || length > CHUNK_BYTES || length > in.available()
                || index >= upload.chunkCount) {
            UPLOADS.remove(player.getUuid());
            return;
        }
        byte[] chunk = in.readNBytes(length);
        int offset = index * CHUNK_BYTES;
        if (chunk.length != length || offset + length > upload.bytes.length
                || (index < upload.chunkCount - 1 && length != CHUNK_BYTES)
                || (index == upload.chunkCount - 1 && offset + length != upload.bytes.length)) {
            UPLOADS.remove(player.getUuid());
            return;
        }
        System.arraycopy(chunk, 0, upload.bytes, offset, length);
        upload.nextChunk++;
        if (upload.nextChunk == upload.chunkCount) {
            UPLOADS.remove(player.getUuid());
            if (!sha256(upload.bytes).equals(upload.hash)) {
                return;
            }
            finish(player, upload);
        }
    }

    private static void finish(ServerPlayerEntity source, Upload upload) throws Exception {
        ServerWorld world = source.getServerWorld();
        if (!world.getBlockState(upload.pos).isOf(Blocks.NOTE_BLOCK)) {
            return;
        }
        String key = key(world, upload.pos);
        if (!ACTIVE.containsKey(key) && ACTIVE.size() >= MAX_ACTIVE_SONGS) {
            return;
        }
        stopAt(world, upload.pos);
        ACTIVE.put(key, new ActiveSong(world, upload.pos, upload.id));
        CLIENT_CACHES.computeIfAbsent(source.getUuid(), ignored -> new HashSet<>()).add(upload.hash);

        byte[] header = encode(out -> {
            out.writeByte(10);
            writeUuid(out, upload.id);
            out.writeUTF(world.getRegistryKey().getValue().toString());
            writePos(out, upload.pos);
            out.writeUTF(upload.hash);
            out.writeUTF(upload.name);
            out.writeInt(upload.bytes.length);
            out.writeInt(upload.chunkCount);
        });
        byte[][] chunks = new byte[upload.chunkCount][];
        for (int i = 0; i < upload.chunkCount; i++) {
            int start = i * CHUNK_BYTES;
            chunks[i] = Arrays.copyOfRange(upload.bytes, start, Math.min(start + CHUNK_BYTES, upload.bytes.length));
        }
        for (ServerPlayerEntity recipient : source.getServer().getPlayerManager().getPlayerList()) {
            if (recipient.getServerWorld() != world
                    || recipient.squaredDistanceTo(upload.pos.toCenterPos()) > RANGE * RANGE
                    || !ServerPlayNetworking.canSend(recipient, SongPacket.ID)) {
                continue;
            }
            ServerPlayNetworking.send(recipient, new SongPacket(header));
            if (!CLIENT_CACHES.getOrDefault(recipient.getUuid(), Set.of()).contains(upload.hash)) {
                for (int i = 0; i < chunks.length; i++) {
                    int chunkIndex = i;
                    byte[] data = encode(out -> {
                        out.writeByte(11);
                        writeUuid(out, upload.id);
                        out.writeInt(chunkIndex);
                        out.writeInt(chunks[chunkIndex].length);
                        out.write(chunks[chunkIndex]);
                    });
                    ServerPlayNetworking.send(recipient, new SongPacket(data));
                }
            }
        }
    }

    private static void stopRequest(ServerPlayerEntity player, DataInputStream in) throws Exception {
        BlockPos pos = readPos(in);
        if (player.squaredDistanceTo(pos.toCenterPos()) <= RANGE * RANGE) {
            stopAt(player.getServerWorld(), pos);
        }
    }

    private static void rememberCache(ServerPlayerEntity player, DataInputStream in) throws Exception {
        String hash = in.readUTF();
        if (hash.matches("[0-9a-f]{64}")) {
            CLIENT_CACHES.computeIfAbsent(player.getUuid(), ignored -> new HashSet<>()).add(hash);
        }
    }

    static void stopAt(World world, BlockPos pos) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        ActiveSong active = ACTIVE.remove(key(serverWorld, pos));
        if (active == null) {
            return;
        }
        byte[] stop = encode(out -> {
            out.writeByte(12);
            writeUuid(out, active.id);
            writePos(out, pos);
        });
        MinecraftServer server = serverWorld.getServer();
        for (ServerPlayerEntity recipient : server.getPlayerManager().getPlayerList()) {
            if (recipient.getServerWorld() == serverWorld
                    && recipient.squaredDistanceTo(pos.toCenterPos()) <= RANGE * RANGE
                    && ServerPlayNetworking.canSend(recipient, SongPacket.ID)) {
                ServerPlayNetworking.send(recipient, new SongPacket(stop));
            }
        }
    }

    static void clear() {
        UPLOADS.clear();
        CLIENT_CACHES.clear();
        ACTIVE.clear();
    }

    static void removePlayer(UUID playerId) {
        UPLOADS.remove(playerId);
        CLIENT_CACHES.remove(playerId);
    }

    private static String key(ServerWorld world, BlockPos pos) {
        return world.getRegistryKey().getValue() + "@" + pos.asLong();
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private static byte[] encode(Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writer.write(out);
            out.flush();
            return bytes.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws Exception {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static void writePos(DataOutputStream out, BlockPos pos) throws Exception {
        out.writeInt(pos.getX());
        out.writeInt(pos.getY());
        out.writeInt(pos.getZ());
    }

    private static BlockPos readPos(DataInputStream in) throws Exception {
        return new BlockPos(in.readInt(), in.readInt(), in.readInt());
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream out) throws Exception;
    }

    private record ActiveSong(ServerWorld world, BlockPos pos, UUID id) {
    }

    private static final class Upload {
        private final UUID id;
        private final BlockPos pos;
        private final int length;
        private final int chunkCount;
        private final String hash;
        private final String name;
        private final byte[] bytes;
        private int nextChunk;

        private Upload(UUID id, BlockPos pos, int length, int chunkCount, String hash, String name, byte[] bytes) {
            this.id = id;
            this.pos = pos;
            this.length = length;
            this.chunkCount = chunkCount;
            this.hash = hash;
            this.name = name;
            this.bytes = bytes;
        }
    }
}