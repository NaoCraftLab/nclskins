package com.naocraftlab.skins.compat.client.identifier.submission.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.compat.client.identifier.measurement.AvatarBodyCapture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.WingsLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WingsLayer.class)
abstract class WingsLayerFitMixin {
    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
            at = @At("HEAD"), cancellable = true, require = 1, expect = 1, allow = 1)
    private void nclskins$bodyOnly(PoseStack poses, SubmitNodeCollector collector, int light,
            HumanoidRenderState state, float yaw, float pitch, CallbackInfo callback) {
        if (AvatarBodyCapture.excludes(state)) callback.cancel();
    }
}
