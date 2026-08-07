package com.naocraftlab.skins.compat.mc262.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.client.EditorPreviewLayerGuard;
import com.naocraftlab.skins.compat.mc262.NclSkinsWideDepthState;
import com.naocraftlab.skins.compat.mc262.Minecraft262PreviewContext;
import com.naocraftlab.skins.compat.mc262.PreviewRenderFailureSink;
import net.minecraft.client.gui.render.pip.GuiEntityRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(GuiEntityRenderer.class)
abstract class GuiEntityRendererMixin {
    @WrapMethod(method = "renderToTexture(Lnet/minecraft/client/renderer/state/gui/pip/GuiEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V")
    private void nclskins$guardEditorPreview(
            GuiEntityRenderState guiState,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            Operation<Void> original) {
        PreviewRenderFailureSink layerSink = nclskins$layerSink(guiState);
        Minecraft262PreviewContext previewContext = nclskins$previewContext(guiState);
        try (var ignoredContext = previewContext == null
                        ? null
                        : previewContext.open(Minecraft.getInstance());
                EditorPreviewLayerGuard ignored = layerSink == null
                ? null
                : EditorPreviewLayerGuard.open(layerSink::onFailure)) {
            original.call(guiState, poseStack, collector);
        } catch (RuntimeException failure) {
            if (!nclskins$handle(guiState, failure)) {
                throw failure;
            }
        }
    }

    private static Minecraft262PreviewContext nclskins$previewContext(
            GuiEntityRenderState guiState) {
        if (guiState.renderState() instanceof NclSkinsWideDepthState previewState) {
            return previewState.nclskins$previewContext();
        }
        return null;
    }

    private static PreviewRenderFailureSink nclskins$layerSink(GuiEntityRenderState guiState) {
        if (guiState.renderState() instanceof AvatarRenderState avatarState
                && avatarState instanceof NclSkinsWideDepthState previewState) {
            return previewState.nclskins$layerFailureSink();
        }
        return null;
    }

    private static boolean nclskins$handle(
            GuiEntityRenderState guiState,
            RuntimeException failure) {
        if (guiState.renderState() instanceof AvatarRenderState avatarState
                && avatarState instanceof NclSkinsWideDepthState previewState) {
            PreviewRenderFailureSink sink = previewState.nclskins$failureSink();
            if (sink != null) {
                sink.onFailure(failure);
                return true;
            }
        }
        return false;
    }
}
