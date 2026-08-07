package com.naocraftlab.skins.compat.mc262.mixin;

import com.naocraftlab.skins.compat.mc262.NclBakedPlayerRenderState;
import com.naocraftlab.skins.compat.mc262.NclBakedPlayerRenderer;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(GuiRenderer.class)
abstract class GuiRendererMixin {
    @ModifyVariable(
            method = "<init>",
            at = @At(value = "LOAD", ordinal = 0),
            argsOnly = true)
    private List<?> nclskins$registerBakedPlayerRenderer(
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
                            == NclBakedPlayerRenderState.class)) {
                throw new IllegalStateException("NCL baked player PIP renderer is already registered");
            }
            extended.add(new NclBakedPlayerRenderer(bufferSource));
        } else {
            extended.add(nclskins$neoForgeRegistration(first, renderers));
        }
        return List.copyOf(extended);
    }

    private static Object nclskins$neoForgeRegistration(Object sample, List<?> registrations) {
        Class<?> registrationType = sample.getClass();
        try {
            Method stateClass = registrationType.getMethod("stateClass");
            for (Object registration : registrations) {
                if (stateClass.invoke(registration) == NclBakedPlayerRenderState.class) {
                    throw new IllegalStateException(
                            "NCL baked player PIP renderer is already registered");
                }
            }
            Constructor<?> constructor = registrationType.getConstructor(
                    Class.class, Function.class);
            Function<MultiBufferSource.BufferSource, PictureInPictureRenderer<?>> factory =
                    NclBakedPlayerRenderer::new;
            return constructor.newInstance(NclBakedPlayerRenderState.class, factory);
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
