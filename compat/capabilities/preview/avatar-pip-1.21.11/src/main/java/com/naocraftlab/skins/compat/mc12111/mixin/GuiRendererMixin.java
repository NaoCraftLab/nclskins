package com.naocraftlab.skins.compat.mc12111.mixin;

import com.naocraftlab.skins.compat.mc12111.Minecraft12111BakedPreviewRenderState;
import com.naocraftlab.skins.compat.mc12111.Minecraft12111BakedPreviewRenderer;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
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
    private List<?> nclskins$registerBakedPreviewRenderer(
            List<?> renderers,
            GuiRenderState renderState,
            MultiBufferSource.BufferSource bufferSource,
            SubmitNodeCollector collector,
            FeatureRenderDispatcher featureRenderDispatcher,
            List<PictureInPictureRenderer<?>> originalRenderers) {
        if (renderers.isEmpty()) {
            throw new IllegalStateException("Vanilla PIP renderer registrations are missing");
        }

        List<Object> extended = new ArrayList<>(renderers);
        Object first = renderers.getFirst();
        if (first instanceof PictureInPictureRenderer<?>) {
            if (renderers.stream()
                    .map(PictureInPictureRenderer.class::cast)
                    .anyMatch(renderer -> renderer.getRenderStateClass()
                            == Minecraft12111BakedPreviewRenderState.class)) {
                throw new IllegalStateException("NCL baked preview PIP renderer is already registered");
            }
            extended.add(new Minecraft12111BakedPreviewRenderer(bufferSource));
        } else {
            extended.add(nclskins$neoForgeRegistration(first, renderers));
        }
        return List.copyOf(extended);
    }

    private static Object nclskins$neoForgeRegistration(
            Object sample, List<?> registrations) {
        Class<?> registrationType = sample.getClass();
        try {
            Method stateClass = registrationType.getMethod("stateClass");
            for (Object registration : registrations) {
                if (stateClass.invoke(registration)
                        == Minecraft12111BakedPreviewRenderState.class) {
                    throw new IllegalStateException(
                            "NCL baked preview PIP renderer is already registered");
                }
            }
            Constructor<?> constructor = registrationType.getConstructor(
                    Class.class, Function.class);
            Function<MultiBufferSource.BufferSource, PictureInPictureRenderer<?>> factory =
                    Minecraft12111BakedPreviewRenderer::new;
            return constructor.newInstance(
                    Minecraft12111BakedPreviewRenderState.class, factory);
        } catch (NoSuchMethodException | IllegalAccessException | InstantiationException failure) {
            throw new IllegalStateException(
                    "Unsupported PIP renderer registration contract", failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("PIP renderer registration failed", cause);
        }
    }
}
