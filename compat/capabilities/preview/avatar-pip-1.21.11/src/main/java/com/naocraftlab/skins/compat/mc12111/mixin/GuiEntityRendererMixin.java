package com.naocraftlab.skins.compat.mc12111.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.client.EditorPreviewLayerGuard;
import com.naocraftlab.skins.compat.mc12111.Minecraft12111PreviewContext;
import com.naocraftlab.skins.compat.mc12111.Minecraft12111PreviewFailureSink;
import com.naocraftlab.skins.compat.mc12111.NclPreviewState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.GuiEntityRenderer;
import net.minecraft.client.gui.render.state.pip.GuiEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(GuiEntityRenderer.class)
abstract class GuiEntityRendererMixin {
    @WrapMethod(method = "renderToTexture(Lnet/minecraft/client/gui/render/state/pip/GuiEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V")
    private void nclskins$guardEditorPreview(
            GuiEntityRenderState guiState,
            PoseStack poseStack,
            Operation<Void> original) {
        NclPreviewState preview = nclskins$preview(guiState);
        Minecraft12111PreviewContext previewContext = preview == null
                ? null
                : preview.nclskins$previewContext();
        Minecraft12111PreviewFailureSink layerSink = preview == null
                ? null
                : preview.nclskins$layerFailureSink();
        try (var ignoredContext = previewContext == null
                        ? null
                        : previewContext.open(Minecraft.getInstance());
                EditorPreviewLayerGuard ignoredLayers = layerSink == null
                        ? null
                        : EditorPreviewLayerGuard.open(layerSink::onFailure)) {
            original.call(guiState, poseStack);
        } catch (RuntimeException failure) {
            Minecraft12111PreviewFailureSink sink = preview == null
                    ? null
                    : preview.nclskins$failureSink();
            if (sink == null) {
                throw failure;
            }
            sink.onFailure(failure);
        }
    }

    private static NclPreviewState nclskins$preview(GuiEntityRenderState guiState) {
        if (guiState.renderState() instanceof NclPreviewState preview
                && preview.nclskins$isEditorPreview()) {
            return preview;
        }
        return null;
    }
}
