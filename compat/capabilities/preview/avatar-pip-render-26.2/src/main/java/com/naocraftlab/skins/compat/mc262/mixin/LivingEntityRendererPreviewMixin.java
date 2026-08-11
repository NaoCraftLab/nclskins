package com.naocraftlab.skins.compat.mc262.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.client.EditorPreviewLayerGuard;
import com.naocraftlab.skins.compat.mc262.Minecraft262PreviewModelAnchors;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererPreviewMixin {
    @Shadow
    protected EntityModel<?> model;

    @Redirect(
            method = "submit",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/EntityRenderState;FF)V"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void nclskins$isolateEditorLayer(
            RenderLayer layer,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            int light,
            EntityRenderState state,
            float yRot,
            float xRot) {
        boolean editorPreview = EditorPreviewLayerGuard.isActive();
        if (!(state instanceof AvatarRenderState)) {
            layer.submit(poseStack, collector, light, state, yRot, xRot);
            return;
        }
        try (Minecraft262PreviewModelAnchors ignored = Minecraft262PreviewModelAnchors.open(model)) {
            layer.submit(poseStack, collector, light, state, yRot, xRot);
        } catch (RuntimeException failure) {
            if (!editorPreview || !EditorPreviewLayerGuard.handle(failure)) {
                throw failure;
            }
        }
    }
}
