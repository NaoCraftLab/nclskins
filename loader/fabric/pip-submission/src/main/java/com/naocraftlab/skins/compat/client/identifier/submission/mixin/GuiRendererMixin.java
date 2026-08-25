package com.naocraftlab.skins.compat.client.identifier.submission.mixin;

import com.google.common.collect.ImmutableMap;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.naocraftlab.skins.compat.client.identifier.submission.BakedPreviewRenderState;
import com.naocraftlab.skins.compat.client.identifier.submission.BakedPreviewRenderer;
import com.naocraftlab.skins.compat.client.identifier.submission.LivePreviewRenderState;
import com.naocraftlab.skins.compat.client.identifier.submission.LivePreviewRenderer;
import java.util.List;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;


@Mixin(GuiRenderer.class)
abstract class GuiRendererMixin {
    @ModifyExpressionValue(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/ImmutableMap$Builder;buildOrThrow()Lcom/google/common/collect/ImmutableMap;"),
            require = 1,
            expect = 1,
            allow = 1)
    private ImmutableMap<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>>
            nclskins$registerPreviewRenderers(
            ImmutableMap<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> original,
            GuiRenderState renderState,
            MultiBufferSource.BufferSource bufferSource,
            SubmitNodeCollector collector,
            FeatureRenderDispatcher featureRenderDispatcher,
            List<PictureInPictureRenderer<?>> originalRenderers) {
        if (original.isEmpty()) {
            throw new IllegalStateException("Vanilla PIP renderer registrations are missing");
        }
        if (original.containsKey(BakedPreviewRenderState.class)
                || original.containsKey(LivePreviewRenderState.class)) {
            throw new IllegalStateException("An NCL preview PIP renderer is already registered");
        }

        return ImmutableMap.<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>>builder()
                .putAll(original)
                .put(BakedPreviewRenderState.class,
                        new BakedPreviewRenderer(bufferSource))
                .put(LivePreviewRenderState.class,
                        new LivePreviewRenderer(bufferSource))
                .buildOrThrow();
    }
}
