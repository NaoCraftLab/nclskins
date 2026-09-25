package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.mixin;

import com.naocraftlab.skins.runtime.CapeProjection;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerCapeMixin {
    @Inject(method = "handlePlayerInfoUpdate(Lnet/minecraft/network/protocol/game/ClientboundPlayerInfoUpdatePacket;)V", at = @At("TAIL"), require = 1, expect = 1, allow = 1)
    private void nclskins$trackedCapePlayers(ClientboundPlayerInfoUpdatePacket packet, CallbackInfo callback) {
        if (!packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) return;
        ClientPacketListener listener = (ClientPacketListener) (Object) this;
        for (var entry : packet.entries()) {
            PlayerInfo info = listener.getPlayerInfo(entry.profileId());
            if (info != null && info.getProfile().getId() != null && info.getProfile().getName() != null) {
                CapeProjection.playerInfoUpdated(
                        info.getProfile().getId(), info.getProfile().getName());
            }
        }
    }

    @Inject(method = "handlePlayerInfoRemove(Lnet/minecraft/network/protocol/game/ClientboundPlayerInfoRemovePacket;)V", at = @At("TAIL"), require = 1, expect = 1, allow = 1)
    private void nclskins$untrackedCapePlayers(ClientboundPlayerInfoRemovePacket packet, CallbackInfo callback) {
        for (var profileId : packet.profileIds()) {
            CapeProjection.untrackedPlayer(profileId);
        }
    }

    @Inject(method = "handleLogin(Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;)V", at = @At("TAIL"), require = 1, expect = 1, allow = 1)
    private void nclskins$capeLogin(ClientboundLoginPacket packet, CallbackInfo callback) {
        CapeProjection.worldEntered();
    }

    @Inject(method = "handleRespawn(Lnet/minecraft/network/protocol/game/ClientboundRespawnPacket;)V", at = @At("TAIL"), require = 1, expect = 1, allow = 1)
    private void nclskins$capeRespawn(ClientboundRespawnPacket packet, CallbackInfo callback) {
        CapeProjection.worldEntered();
    }
}
