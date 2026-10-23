package com.naocraftlab.skins.compat.client.identifier.foil.buffered;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(CustomFeatureRenderer.class)
public abstract class CapturedGeometryMixin {
    @WrapOperation(method = "renderSolid(Lnet/minecraft/client/renderer/SubmitNodeCollection;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;getBuffer(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"),
            require = 1, expect = 1, allow = 1)
    private VertexConsumer nclskins$acquireLiveSolidCape(MultiBufferSource.BufferSource receiver, RenderType type,
            Operation<VertexConsumer> original) {
        CapturedCapePass capture = CapturedCapePass.of(type);
        if (capture != null && !capture.isValid()) return null;
        return original.call(receiver, type);
    }

    @WrapOperation(method = "renderSolid(Lnet/minecraft/client/renderer/SubmitNodeCollection;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;render(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V"),
            require = 1, expect = 1, allow = 1)
    private void nclskins$emitAcquiredSolidCape(SubmitNodeCollector.CustomGeometryRenderer receiver,
            PoseStack.Pose pose, VertexConsumer consumer, Operation<Void> original) {
        if (consumer != null) original.call(receiver, pose, consumer);
    }

    @WrapOperation(method = "renderTranslucent(Lnet/minecraft/client/renderer/SubmitNodeCollection;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;getBuffer(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"),
            require = 1, expect = 1, allow = 1)
    private VertexConsumer nclskins$acquireLiveTranslucentCape(MultiBufferSource.BufferSource receiver, RenderType type,
            Operation<VertexConsumer> original) {
        CapturedCapePass capture = CapturedCapePass.of(type);
        if (capture != null && !capture.isValid()) return null;
        return original.call(receiver, type);
    }

    @WrapOperation(method = "renderTranslucent(Lnet/minecraft/client/renderer/SubmitNodeCollection;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;render(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V"),
            require = 1, expect = 1, allow = 1)
    private void nclskins$emitAcquiredTranslucentCape(SubmitNodeCollector.CustomGeometryRenderer receiver,
            PoseStack.Pose pose, VertexConsumer consumer, Operation<Void> original) {
        if (consumer != null) original.call(receiver, pose, consumer);
    }

}
