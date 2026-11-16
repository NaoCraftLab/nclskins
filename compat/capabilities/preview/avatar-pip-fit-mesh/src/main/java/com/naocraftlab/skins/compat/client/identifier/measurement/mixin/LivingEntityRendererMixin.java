package com.naocraftlab.skins.compat.client.identifier.measurement.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.compat.client.identifier.measurement.AvatarBodyCapture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererMixin {
    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"), require = 1, expect = 1, allow = 1)
    private void nclskins$bodyRoot(LivingEntityRenderState state, PoseStack poses, SubmitNodeCollector collector,
            CameraRenderState camera, CallbackInfo callback) {
        AvatarBodyCapture.root(state, poses.last().pose());
    }
}
