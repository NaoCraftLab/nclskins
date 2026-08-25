package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.client.EditorPreviewLayerGuard;
import com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.PreviewModelAnchors;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererPreviewMixin {
    @Shadow
    protected EntityModel<?> model;

    @WrapOperation(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/Entity;FFFFFF)V"),
            require = 1, expect = 1, allow = 1)
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
            float headPitch,
            Operation<Void> original) {
        boolean editorPreview = EditorPreviewLayerGuard.isActive();
        if (!(entity instanceof Player)) {
            original.call(layer,
                    poseStack, buffers, light, entity, limbSwing, limbSwingAmount,
                    partialTick, ageInTicks, netHeadYaw, headPitch);
            return;
        }
        boolean slim = entity instanceof AbstractClientPlayer player
                && player.getSkin().model() == PlayerSkin.Model.SLIM;
        try (PreviewModelAnchors ignored =
                     PreviewModelAnchors.open(model, slim)) {
            original.call(layer,
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
            if (!editorPreview || !EditorPreviewLayerGuard.handle(failure)) {
                throw failure;
            }
        }
    }
}
