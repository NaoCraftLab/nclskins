package com.naocraftlab.skins.compat.mc12111.mixin;

import com.naocraftlab.skins.compat.mc12111.Minecraft12111BakedPreviewRenderState;
import com.naocraftlab.skins.compat.mc12111.Minecraft12111BakedPreviewRenderer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;


@Mixin(GuiRenderer.class)
abstract class GuiRendererMixin {
    @ModifyVariable(
            method = "<init>",
            at = @At(value = "LOAD", ordinal = 0),
            argsOnly = true)
    private List<PictureInPictureRenderer<?>> nclskins$registerBakedPreviewRenderer(
            List<PictureInPictureRenderer<?>> renderers,
            GuiRenderState renderState,
            MultiBufferSource.BufferSource bufferSource,
            SubmitNodeCollector collector,
            FeatureRenderDispatcher featureRenderDispatcher,
            List<PictureInPictureRenderer<?>> originalRenderers) {
        if (renderers.isEmpty()) {
            throw new IllegalStateException("Vanilla PIP renderer registrations are missing");
        }
        if (renderers.stream().anyMatch(renderer -> renderer.getRenderStateClass()
                == Minecraft12111BakedPreviewRenderState.class)) {
            throw new IllegalStateException("NCL baked preview PIP renderer is already registered");
        }

        List<PictureInPictureRenderer<?>> extended = new ArrayList<>(renderers);
        extended.add(new Minecraft12111BakedPreviewRenderer(bufferSource));
        return List.copyOf(extended);
    }
}
