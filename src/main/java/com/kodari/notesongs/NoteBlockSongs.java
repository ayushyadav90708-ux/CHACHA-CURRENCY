package com.kodari.notesongs;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class NoteBlockSongs implements ModInitializer {
    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(SongPacket.ID, SongPacket.CODEC);
        PayloadTypeRegistry.playS2C().register(SongPacket.ID, SongPacket.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SongPacket.ID, (payload, context) ->
                context.server().execute(() -> PlaybackServer.handle(context.player(), payload.data())));
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) ->
                PlaybackServer.stopAt(world, pos));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                PlaybackServer.removePlayer(handler.player.getUuid()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> PlaybackServer.clear());
    }
}