package com.naocraftlab.skins.compat.client.resourcelocation.optifine.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.OfficialCapeSource;
import com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.PreviewScope;
import com.naocraftlab.skins.runtime.CapeProjection;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractClientPlayer.class)
abstract class OptifinePlayerCapeMixin {
    @ModifyReturnValue(method = "getCloakTextureLocation()Lnet/minecraft/resources/ResourceLocation;", at = @At("RETURN"), require = 3, expect = 3, allow = 3)
    private ResourceLocation nclskins$cape(ResourceLocation original) {
        AbstractClientPlayer player = (AbstractClientPlayer) (Object) this;
        ResourceLocation resolved = nclskins$location(nclskins$resolve().capeLocation());
        return PreviewScope.cape(player, resolved);
    }

    @ModifyReturnValue(method = "getElytraTextureLocation()Lnet/minecraft/resources/ResourceLocation;", at = @At("RETURN"), require = 1, expect = 1, allow = 1)
    private ResourceLocation nclskins$elytra(ResourceLocation original) {
        AbstractClientPlayer player = (AbstractClientPlayer) (Object) this;
        CapeProjection.Result resolved = nclskins$resolve();
        ResourceLocation selected = resolved.capeLocation() == null ? null
                : nclskins$location(resolved.hasElytra() ? resolved.elytraLocation()
                        : "minecraft:textures/entity/elytra.png");
        return PreviewScope.elytra(player, selected);
    }

    @ModifyReturnValue(method = "hasElytraCape()Z", at = @At("RETURN"), remap = false, require = 3, expect = 3, allow = 3)
    private boolean nclskins$hasElytraCape(boolean original) {
        AbstractClientPlayer player = (AbstractClientPlayer) (Object) this;
        Boolean preview = PreviewScope.textureLoaded(player, PreviewScope.Texture.CAPE);
        if (preview != null) {
            return preview && Boolean.TRUE.equals(PreviewScope.textureLoaded(player, PreviewScope.Texture.ELYTRA));
        }
        CapeProjection.Result resolved = nclskins$resolve();
        return resolved.capeLocation() != null && resolved.hasElytra();
    }

    private CapeProjection.Result nclskins$resolve() {
        AbstractClientPlayer player = (AbstractClientPlayer) (Object) this;
        GameProfile profile = player.getGameProfile();
        Minecraft minecraft = Minecraft.getInstance();
        boolean self = minecraft.getUser().getProfileId().equals(profile.getId());
        CapeProjection.Candidate minecraftCape = null;
        if (!self) {
            ClientPacketListener connection = minecraft.getConnection();
            PlayerInfo info = connection == null ? null : connection.getPlayerInfo(profile.getId());
            if (info != null && info.getProfile().getId().equals(profile.getId())) {
                minecraftCape = ((OfficialCapeSource) info).nclskins$officialCape();
            }
        }
        return CapeProjection.resolve(profile.getId(), profile.getName(), null, minecraftCape, self);
    }

    private static ResourceLocation nclskins$location(String location) {
        return location == null ? null : ResourceLocation.tryParse(location);
    }
}
