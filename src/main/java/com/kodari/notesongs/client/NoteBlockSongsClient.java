package com.kodari.notesongs.client;

import com.kodari.notesongs.SongPacket;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.ActionResult;

public final class NoteBlockSongsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientConfig.load();
        ClientSongManager.initialize();
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.getBlockState(hit.getBlockPos()).isOf(Blocks.NOTE_BLOCK)) {
                return ActionResult.PASS;
            }
            if (world.isClient) {
                MinecraftClient.getInstance().setScreen(new SongsScreen(hit.getBlockPos()));
            }
            return ActionResult.SUCCESS;
        });
        ClientPlayNetworking.registerGlobalReceiver(SongPacket.ID, (payload, context) ->
                context.client().execute(() -> ClientSongManager.handle(payload.data())));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ClientSongManager.sendCacheManifest());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null && client.world != null) {
                ClientSongManager.updateListener(client.player.getCameraPosVec(1.0f), client.player.getYaw());
            } else {
                ClientSongManager.stopAll();
            }
        });
    }
}