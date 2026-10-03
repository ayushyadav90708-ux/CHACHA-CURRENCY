package com.kodari.notesongs.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

final class SongsScreen extends Screen {
    private final BlockPos notePos;
    private List<Path> songs = List.of();
    private TextFieldWidget search;
    private Path selected;
    private int scroll;

    SongsScreen(BlockPos notePos) {
        super(Text.literal("Songs"));
        this.notePos = notePos.toImmutable();
        refresh();
    }

    @Override
    protected void init() {
        int left = width / 2 - 150;
        search = addDrawableChild(new TextFieldWidget(textRenderer, left, 42, 300, 20, Text.literal("Search songs")));
        search.setPlaceholder(Text.literal("Search songs..."));
        addDrawableChild(ButtonWidget.builder(Text.literal("Play"), button -> {
            if (selected != null) {
                ClientSongManager.play(notePos, selected);
            }
        }).dimensions(left, height - 36, 48, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Stop"), button -> ClientSongManager.stop(notePos))
                .dimensions(left + 52, height - 36, 48, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Vol -"), button -> ClientConfig.volume(ClientConfig.volume() - 0.1f))
                .dimensions(left + 104, height - 36, 48, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Vol +"), button -> ClientConfig.volume(ClientConfig.volume() + 0.1f))
                .dimensions(left + 156, height - 36, 48, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), button -> refresh())
                .dimensions(left + 208, height - 36, 48, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> close())
                .dimensions(left + 260, height - 36, 40, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        int left = width / 2 - 150;
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 18, 0xFFFFFF);
        String playing = "Playing: " + ClientSongManager.currentlyPlaying(notePos);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(playing, 300)),
                width / 2, 29, 0xAAAAAA);
        context.drawTextWithShadow(textRenderer, Text.literal("Volume: " + Math.round(ClientConfig.volume() * 100) + "%"),
                left, height - 52, 0xD0D0D0);
        List<Path> filtered = filteredSongs();
        int rowY = 72;
        int rowHeight = 20;
        int visible = Math.max(1, (height - 122) / rowHeight);
        if (filtered.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("No MP3 files found in " + ClientSongManager.songsFolder()),
                    width / 2, rowY + 8, 0xAAAAAA);
            return;
        }
        scroll = Math.max(0, Math.min(scroll, Math.max(0, filtered.size() - visible)));
        for (int i = scroll; i < Math.min(filtered.size(), scroll + visible); i++) {
            Path path = filtered.get(i);
            int y = rowY + (i - scroll) * rowHeight;
            boolean isSelected = path.equals(selected);
            context.fill(left, y, left + 300, y + rowHeight - 1, isSelected ? 0xFF465D75 : 0xFF252525);
            context.drawTextWithShadow(textRenderer, Text.literal("♫ " + displayName(path)), left + 7, y + 6,
                    isSelected ? 0xFFFFFF : 0xD0D0D0);
        }
        context.drawTextWithShadow(textRenderer, Text.literal("Selected: " + (selected == null ? "none" : displayName(selected))),
                left, height - 66, 0xAAAAAA);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        int left = width / 2 - 150;
        int rowY = 72;
        int visible = Math.max(1, (height - 122) / 20);
        List<Path> filtered = filteredSongs();
        if (mouseX >= left && mouseX <= left + 300 && mouseY >= rowY) {
            int row = (int) ((mouseY - rowY) / 20);
            int index = scroll + row;
            if (row < visible && index >= 0 && index < filtered.size()) {
                selected = filtered.get(index);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int visible = Math.max(1, (height - 122) / 20);
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(verticalAmount),
                Math.max(0, filteredSongs().size() - visible)));
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void refresh() {
        songs = ClientSongManager.listSongs();
        if (selected != null && !songs.contains(selected)) {
            selected = null;
        }
        scroll = 0;
    }

    private List<Path> filteredSongs() {
        String query = search == null ? "" : search.getText().toLowerCase(Locale.ROOT).trim();
        return songs.stream().filter(path -> displayName(path).toLowerCase(Locale.ROOT).contains(query)).toList();
    }

    private static String displayName(Path path) {
        String name = path.getFileName().toString();
        return name.substring(0, name.length() - 4).replace('_', ' ');
    }
}