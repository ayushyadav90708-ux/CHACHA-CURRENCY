package com.kodari.notesongs.client;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

final class ClientConfig {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("notesongs.properties");
    private static float volume = 0.8f;
    private static int range = 32;
    private static int maxFileSizeMb = 8;

    private ClientConfig() {
    }

    static void load() {
        Properties properties = new Properties();
        try {
            if (Files.exists(FILE)) {
                try (InputStream input = Files.newInputStream(FILE)) {
                    properties.load(input);
                }
            }
            volume = clampFloat(Float.parseFloat(properties.getProperty("volume", "0.8")), 0.0f, 1.0f);
            range = clamp(Integer.parseInt(properties.getProperty("range", "32")), 8, 48);
            maxFileSizeMb = clamp(Integer.parseInt(properties.getProperty("maxFileSizeMb", "8")), 1, 8);
        } catch (Exception ignored) {
            volume = 0.8f;
            range = 32;
            maxFileSizeMb = 8;
        }
        save();
    }

    static float volume() {
        return volume;
    }

    static void volume(float value) {
        volume = clampFloat(value, 0.0f, 1.0f);
        save();
    }

    static int range() {
        return range;
    }

    static int maxFileBytes() {
        return maxFileSizeMb * 1024 * 1024;
    }

    private static void save() {
        Properties properties = new Properties();
        properties.setProperty("volume", Float.toString(volume));
        properties.setProperty("range", Integer.toString(range));
        properties.setProperty("maxFileSizeMb", Integer.toString(maxFileSizeMb));
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream output = Files.newOutputStream(FILE)) {
                properties.store(output, "Note Block Songs client settings");
            }
        } catch (IOException ignored) {
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}