package com.kodari.notesongs.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import com.kodari.notesongs.SongPacket;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ClientSongManager {
    private static final int CHUNK_BYTES = 24 * 1024;
    private static final Path SONGS = FabricLoader.getInstance().getGameDir().resolve("Songs");
    private static final Path CACHE = FabricLoader.getInstance().getConfigDir().resolve("notesongs-cache");
    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Map<String, BlockPos> ACTIVE_POSITIONS = new ConcurrentHashMap<>();
    private static final Map<String, String> ACTIVE_NAMES = new ConcurrentHashMap<>();
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "NoteSongs-Decoder");
        thread.setDaemon(true);
        return thread;
    });

    private ClientSongManager() {
    }

    static Path songsFolder() {
        return SONGS;
    }

    static void initialize() {
        try {
            Files.createDirectories(SONGS);
            Files.createDirectories(CACHE);
        } catch (Exception ignored) {
        }
    }

    static java.util.List<Path> listSongs() {
        initialize();
        try (var paths = Files.list(SONGS)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".mp3"))
                    .sorted()
                    .toList();
        } catch (Exception ignored) {
            return java.util.List.of();
        }
    }

    static void sendCacheManifest() {
        initialize();
        try (var paths = Files.list(CACHE)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".mp3"))
                    .limit(256)
                    .forEach(path -> send(encode(out -> {
                        out.writeByte(4);
                        out.writeUTF(path.getFileName().toString().replace(".mp3", ""));
                    })));
        } catch (Exception ignored) {
        }
    }

    static void play(BlockPos pos, Path file) {
        try {
            long size = Files.size(file);
            if (size < 1 || size > ClientConfig.maxFileBytes()) {
                message("Song must be between 1 byte and " + ClientConfig.maxFileBytes() / (1024 * 1024) + " MiB.");
                return;
            }
            byte[] bytes = Files.readAllBytes(file);
            String hash = sha256(bytes);
            UUID id = UUID.randomUUID();
            int chunks = (bytes.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
            send(encode(out -> {
                out.writeByte(1);
                writeUuid(out, id);
                writePos(out, pos);
                out.writeInt(bytes.length);
                out.writeInt(chunks);
                out.writeUTF(hash);
                String name = file.getFileName().toString();
                out.writeUTF(name.length() > 80 ? name.substring(0, 80) : name);
            }));
            for (int i = 0; i < chunks; i++) {
                int index = i;
                byte[] chunk = Arrays.copyOfRange(bytes, i * CHUNK_BYTES, Math.min((i + 1) * CHUNK_BYTES, bytes.length));
                send(encode(out -> {
                    out.writeByte(2);
                    writeUuid(out, id);
                    out.writeInt(index);
                    out.writeInt(chunk.length);
                    out.write(chunk);
                }));
            }
        } catch (Exception exception) {
            message("Could not load that MP3: " + exception.getMessage());
        }
    }

    static void stop(BlockPos pos) {
        send(encode(out -> {
            out.writeByte(3);
            writePos(out, pos);
        }));
    }

    static void handle(byte[] packet) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(packet))) {
            int action = in.readUnsignedByte();
            if (action == 10) {
                UUID id = readUuid(in);
                String dimension = in.readUTF();
                BlockPos pos = readPos(in);
                String hash = in.readUTF();
                String name = in.readUTF();
                int length = in.readInt();
                int chunks = in.readInt();
                if (length < 1 || length > 8 * 1024 * 1024 || chunks != (length + CHUNK_BYTES - 1) / CHUNK_BYTES
                        || !hash.matches("[0-9a-f]{64}")) {
                    return;
                }
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.world == null || !client.world.getRegistryKey().getValue().toString().equals(dimension)) {
                    return;
                }
                ACTIVE_POSITIONS.put(id.toString(), pos);
                ACTIVE_NAMES.put(id.toString(), name);
                Path cached = CACHE.resolve(hash + ".mp3");
                if (Files.isRegularFile(cached)) {
                    decodeAndPlay(id.toString(), pos, cached);
                } else {
                    PENDING.put(id.toString(), new Pending(pos, hash, name, length, chunks, new byte[chunks][]));
                }
            } else if (action == 11) {
                UUID id = readUuid(in);
                int index = in.readInt();
                int length = in.readInt();
                Pending pending = PENDING.get(id.toString());
                if (pending == null || index < 0 || index >= pending.chunks.length || length < 1
                        || length > CHUNK_BYTES || length > in.available() || pending.chunks[index] != null) {
                    return;
                }
                byte[] chunk = in.readNBytes(length);
                if (chunk.length != length || (index < pending.chunks.length - 1 && length != CHUNK_BYTES)) {
                    PENDING.remove(id.toString());
                    return;
                }
                pending.chunks[index] = chunk;
                pending.received++;
                if (pending.received == pending.chunks.length) {
                    PENDING.remove(id.toString());
                    finishDownload(id.toString(), pending);
                }
            } else if (action == 12) {
                UUID id = readUuid(in);
                BlockPos pos = readPos(in);
                PENDING.remove(id.toString());
                ACTIVE_POSITIONS.remove(id.toString());
                ACTIVE_NAMES.remove(id.toString());
                SpatialAudio.stop(id.toString());
                SpatialAudio.stopAt(pos);
            }
        } catch (Exception ignored) {
        }
    }

    static void updateListener(Vec3d position, float yaw) {
        SpatialAudio.updateListener(position, yaw);
    }

    static String currentlyPlaying(BlockPos pos) {
        for (Map.Entry<String, BlockPos> entry : ACTIVE_POSITIONS.entrySet()) {
            if (entry.getValue().equals(pos)) {
                return ACTIVE_NAMES.getOrDefault(entry.getKey(), "unknown");
            }
        }
        return "none";
    }

    static void stopAll() {
        PENDING.clear();
        ACTIVE_POSITIONS.clear();
        ACTIVE_NAMES.clear();
        SpatialAudio.stopAll();
    }

    private static void finishDownload(String id, Pending pending) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(pending.length);
            for (byte[] chunk : pending.chunks) {
                if (chunk == null) {
                    return;
                }
                output.write(chunk);
            }
            byte[] bytes = output.toByteArray();
            if (bytes.length != pending.length || !sha256(bytes).equals(pending.hash)) {
                return;
            }
            Files.createDirectories(CACHE);
            Path cached = CACHE.resolve(pending.hash + ".mp3");
            Files.write(cached, bytes);
            send(encode(out -> {
                out.writeByte(4);
                out.writeUTF(pending.hash);
            }));
            decodeAndPlay(id, pending.pos, cached);
        } catch (Exception exception) {
            message("Could not cache synchronized song: " + exception.getMessage());
        }
    }

    private static void decodeAndPlay(String id, BlockPos pos, Path file) {
        DECODER.execute(() -> {
            try {
                AudioDecoder.DecodedAudio audio = AudioDecoder.decode(Files.readAllBytes(file));
                MinecraftClient client = MinecraftClient.getInstance();
                client.execute(() -> {
                    if (client.world != null && ACTIVE_POSITIONS.containsKey(id)) {
                        SpatialAudio.play(id, pos, audio);
                    }
                });
            } catch (Exception exception) {
                message("MP3 decode failed: " + exception.getMessage());
            }
        });
    }

    private static void message(String value) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(Text.literal("[Songs] " + value), false);
            }
        });
    }

    private static void send(byte[] packet) {
        ClientPlayNetworking.send(new SongPacket(packet));
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

    private static UUID readUuid(DataInputStream in) throws Exception {
        return new UUID(in.readLong(), in.readLong());
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

    private static final class Pending {
        private final BlockPos pos;
        private final String hash;
        private final String name;
        private final int length;
        private final byte[][] chunks;
        private int received;

        private Pending(BlockPos pos, String hash, String name, int length, int count, byte[][] chunks) {
            this.pos = pos;
            this.hash = hash;
            this.name = name;
            this.length = length;
            this.chunks = chunks;
        }
    }
}