package com.naocraftlab.skins.compat.client.identifier.submission.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.client.EditorPreviewLayerGuard;
import com.naocraftlab.skins.compat.client.identifier.submission.PreviewModelAnchors;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.player.PlayerModelType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;


@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererPreviewMixin {
    @Shadow
    protected EntityModel<?> model;

    @WrapOperation(
            method = "submit",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/EntityRenderState;FF)V"),
            require = 1, expect = 1, allow = 1)
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void nclskins$isolateEditorLayer(
            RenderLayer layer,
            PoseStack poseStack,
            SubmitNodeCollector nodes,
            int light,
            EntityRenderState state,
            float yRot,
            float xRot,
            Operation<Void> original) {
        boolean editorPreview = EditorPreviewLayerGuard.isActive();
        if (!(state instanceof AvatarRenderState avatar)) {
            original.call(layer, poseStack, nodes, light, state, yRot, xRot);
            return;
        }
        boolean slim = avatar.skin != null && avatar.skin.model() == PlayerModelType.SLIM;
        try (PreviewModelAnchors ignored =
                PreviewModelAnchors.open(model, slim)) {
            original.call(layer, poseStack, nodes, light, state, yRot, xRot);
        } catch (RuntimeException failure) {
            if (!editorPreview || !EditorPreviewLayerGuard.handle(failure)) {
                throw failure;
            }
        }
    }
}
