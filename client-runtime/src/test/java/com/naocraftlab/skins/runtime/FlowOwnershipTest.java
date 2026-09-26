package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.PreviewRenderer;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FlowOwnershipTest {
    @Test
    void flowsKeepMutableFieldsPrivateAndContextsDoNotExposeWritableProperties() {
        for (Class<?> flow : List.of(GalleryFlow.class, EditorFlow.class,
                CatalogImportFlow.class, ProviderFlow.class)) {
            for (var field : flow.getDeclaredFields()) {
                assertTrue(Modifier.isPrivate(field.getModifiers()), flow.getSimpleName() + "." + field.getName());
            }
            Class<?> context = Arrays.stream(flow.getDeclaredClasses())
                    .filter(type -> type.getSimpleName().equals("Context")).findFirst().orElseThrow();
            var methods = List.of(context.getDeclaredMethods());
            for (var read : methods) {
                if (read.getParameterCount() != 0 || read.getReturnType() == void.class) continue;
                assertFalse(methods.stream().anyMatch(write -> write.getName().equals(read.getName())
                        && write.getParameterCount() > 0), context.getName() + "." + read.getName());
            }
            assertFalse(methods.stream().anyMatch(method -> method.getName().equals("generation")));
        }
    }

    @Test
    void providerPreviewActionEmitsOnlyItsTypedPreferenceToTheOwner() {
        AtomicReference<PreviewRenderer.CapeMode> preference = new AtomicReference<>();
        ProviderFlow.Context context = (ProviderFlow.Context) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ProviderFlow.Context.class},
                (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "providerPreviewModeChanged" -> preference.set((PreviewRenderer.CapeMode) arguments[0]);
                        case "showProviders", "publish" -> { }
                        default -> throw new AssertionError("Unexpected cross-flow dependency: " + method.getName());
                    }
                    return null;
                });
        ProviderFlow flow = new ProviderFlow(context);

        flow.dispatchProviderWidget("providers.preview_mode");

        assertEquals(PreviewRenderer.CapeMode.ELYTRA, preference.get());
        assertEquals(preference.get(), flow.providerPreview().capeMode());
        flow.acceptPreviewMode(PreviewRenderer.CapeMode.CAPE);
        assertEquals(PreviewRenderer.CapeMode.CAPE, flow.providerPreview().capeMode());
        assertEquals(PreviewRenderer.CapeMode.ELYTRA, preference.get());
    }
}
