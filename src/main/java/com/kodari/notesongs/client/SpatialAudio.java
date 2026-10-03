package com.kodari.notesongs.client;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class SpatialAudio {
    private static final Map<String, Playback> PLAYBACKS = new ConcurrentHashMap<>();
    private static volatile Listener listener = new Listener(Vec3d.ZERO, 0.0f);

    private SpatialAudio() {
    }

    static void updateListener(Vec3d position, float yaw) {
        listener = new Listener(position, yaw);
    }

    static void play(String id, BlockPos pos, AudioDecoder.DecodedAudio audio) {
        stop(id);
        Playback playback = new Playback(pos.toCenterPos(), audio);
        PLAYBACKS.put(id, playback);
        Thread thread = new Thread(playback::run, "NoteSongs-Audio-" + id);
        thread.setDaemon(true);
        thread.start();
    }

    static void stop(String id) {
        Playback playback = PLAYBACKS.remove(id);
        if (playback != null) {
            playback.stop();
        }
    }

    static void stopAt(BlockPos pos) {
        PLAYBACKS.entrySet().removeIf(entry -> {
            if (BlockPos.ofFloored(entry.getValue().position).equals(pos)) {
                entry.getValue().stop();
                return true;
            }
            return false;
        });
    }

    static void stopAll() {
        PLAYBACKS.values().forEach(Playback::stop);
        PLAYBACKS.clear();
    }

    private record Listener(Vec3d position, float yaw) {
    }

    private static final class Playback {
        private final Vec3d position;
        private final AudioDecoder.DecodedAudio audio;
        private final AtomicBoolean running = new AtomicBoolean(true);

        private Playback(Vec3d position, AudioDecoder.DecodedAudio audio) {
            this.position = position;
            this.audio = audio;
        }

        private void stop() {
            running.set(false);
        }

        private void run() {
            SourceDataLine line = null;
            try {
                AudioFormat format = new AudioFormat(audio.sampleRate(), 16, 2, true, false);
                line = AudioSystem.getSourceDataLine(format);
                line.open(format, 16384);
                line.start();
                int frames = audio.samples().length / audio.channels();
                byte[] output = new byte[2048 * 4];
                for (int start = 0; running.get() && start < frames; start += 2048) {
                    Listener current = listener;
                    Vec3d relative = position.subtract(current.position);
                    double distance = Math.sqrt(relative.lengthSquared());
                    double attenuation = Math.max(0.0, 1.0 - distance / ClientConfig.range());
                    double radians = Math.toRadians(current.yaw);
                    double rightX = Math.cos(radians);
                    double rightZ = -Math.sin(radians);
                    double pan = distance < 0.001 ? 0.0 :
                            Math.max(-1.0, Math.min(1.0, (relative.x * rightX + relative.z * rightZ) / distance));
                    double leftGain = ClientConfig.volume() * attenuation * Math.sqrt((1.0 - pan) * 0.5);
                    double rightGain = ClientConfig.volume() * attenuation * Math.sqrt((1.0 + pan) * 0.5);
                    int count = Math.min(2048, frames - start);
                    for (int i = 0; i < count; i++) {
                        int sampleIndex = (start + i) * audio.channels();
                        short left = audio.samples()[sampleIndex];
                        short right = audio.channels() == 1 ? left : audio.samples()[sampleIndex + 1];
                        put(output, i * 4, (short) (left * leftGain));
                        put(output, i * 4 + 2, (short) (right * rightGain));
                    }
                    line.write(output, 0, count * 4);
                }
                if (running.get()) {
                    line.drain();
                }
            } catch (Exception ignored) {
            } finally {
                if (line != null) {
                    line.stop();
                    line.close();
                }
            }
        }

        private static void put(byte[] output, int offset, short sample) {
            output[offset] = (byte) sample;
            output[offset + 1] = (byte) (sample >>> 8);
        }
    }
}