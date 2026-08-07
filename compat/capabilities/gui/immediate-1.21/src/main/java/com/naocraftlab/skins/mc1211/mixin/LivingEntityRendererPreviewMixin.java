package com.naocraftlab.skins.mc1211.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.client.EditorPreviewLayerGuard;
import com.naocraftlab.skins.mc1211.Minecraft1211PreviewModelAnchors;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererPreviewMixin {
    @Shadow
    protected EntityModel<?> model;

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/Entity;FFFFFF)V"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void nclskins$isolateEditorLayer(
            RenderLayer layer,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int light,
            Entity entity,
            float limbSwing,
            float limbSwingAmount,
            float partialTick,
            float ageInTicks,
            float netHeadYaw,
            float headPitch) {
        boolean editorPreview = EditorPreviewLayerGuard.isActive();
        boolean localPlayer = entity == Minecraft.getInstance().player;
        if (!editorPreview && !localPlayer) {
            layer.render(
                    poseStack, buffers, light, entity, limbSwing, limbSwingAmount,
                    partialTick, ageInTicks, netHeadYaw, headPitch);
            return;
        }
        try (Minecraft1211PreviewModelAnchors ignored = Minecraft1211PreviewModelAnchors.open(model)) {
            layer.render(
                    poseStack,
                    buffers,
                    light,
                    entity,
                    limbSwing,
                    limbSwingAmount,
                    partialTick,
                    ageInTicks,
                    netHeadYaw,
                    headPitch);
        } catch (RuntimeException failure) {
            if (!EditorPreviewLayerGuard.handle(failure)) {
                throw failure;
            }
        }
    }
}
