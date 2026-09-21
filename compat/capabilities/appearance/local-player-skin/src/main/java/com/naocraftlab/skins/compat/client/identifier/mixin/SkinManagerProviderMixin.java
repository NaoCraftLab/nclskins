package com.naocraftlab.skins.compat.client.identifier.mixin;

import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SkinManager.class)
public interface SkinManagerProviderMixin {
    @Invoker("registerTextures")
    CompletableFuture<PlayerSkin> nclskins$registerProviderTextures(UUID profileId, MinecraftProfileTextures textures);
}
