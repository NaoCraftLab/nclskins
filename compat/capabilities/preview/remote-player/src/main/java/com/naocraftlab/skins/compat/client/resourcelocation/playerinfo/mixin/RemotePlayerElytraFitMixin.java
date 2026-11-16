package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.compat.client.identifier.measurement.AvatarBodyCapture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ElytraLayer.class)
abstract class RemotePlayerElytraFitMixin {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At("HEAD"), cancellable = true, require = 1, expect = 1, allow = 1)
    private void nclskins$excludeEquipment(PoseStack pose, MultiBufferSource buffers, int light, LivingEntity player,
            float limb, float amount, float partial, float age, float yaw, float pitch, CallbackInfo callback) {
        if (AvatarBodyCapture.excludes(player)) callback.cancel();
    }
}
