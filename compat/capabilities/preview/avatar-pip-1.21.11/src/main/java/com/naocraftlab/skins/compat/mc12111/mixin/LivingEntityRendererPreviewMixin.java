package com.naocraftlab.skins.compat.mc12111.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.client.EditorPreviewLayerGuard;
import com.naocraftlab.skins.compat.mc12111.Minecraft12111PreviewModelAnchors;
import com.naocraftlab.skins.compat.mc12111.NclPreviewState;
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
            SubmitNodeCollector nodes,
            int light,
            EntityRenderState state,
            float yRot,
            float xRot) {
        if (!(state instanceof AvatarRenderState avatar)) {
            layer.submit(poseStack, nodes, light, state, yRot, xRot);
            return;
        }
        boolean slim = avatar.skin != null && avatar.skin.model() == PlayerModelType.SLIM;
        try (Minecraft12111PreviewModelAnchors ignored =
                Minecraft12111PreviewModelAnchors.open(model, slim)) {
            layer.submit(poseStack, nodes, light, state, yRot, xRot);
        } catch (RuntimeException failure) {
            boolean editorPreview = state instanceof NclPreviewState preview
                    && preview.nclskins$isEditorPreview();
            if (!editorPreview || !EditorPreviewLayerGuard.handle(failure)) {
                throw failure;
            }
        }
    }
}
