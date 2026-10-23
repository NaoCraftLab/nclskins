package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.client.PreviewRenderer.*;
import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
import com.naocraftlab.skins.compat.client.identifier.CapeMaterialSnapshot;
import com.naocraftlab.skins.compat.waveycapes.WaveyCapesSupport;
import com.naocraftlab.skins.runtime.*;
import dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

public final class BufferedPreviewCapeProbe {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        runCase(args);
    }
    public static void runCase(String[] args) throws Exception {
        try (HeadlessCapeClient client = HeadlessCapeClient.open(false)) {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            try {
                Class.forName("com.naocraftlab.skins.buildlogic.probe.wavey.BakedPublicPreviewProbe").getMethod("runCase", String[].class)
                        .invoke(null, (Object) new String[]{Path.of(args[0]).resolve("public-baked").toString()});
            } catch (java.lang.reflect.InvocationTargetException failure) {
                if (failure.getCause() instanceof Error error) throw error;
                if (failure.getCause() instanceof Exception exception) throw exception;
                throw failure;
            }
            try {
                Class.forName("com.naocraftlab.skins.buildlogic.probe.wavey.LivePublicPreviewProbe").getMethod("runCase", String[].class)
                        .invoke(null, (Object) new String[]{Path.of(args[0]).resolve("public-live").toString()});
            } catch (java.lang.reflect.InvocationTargetException failure) {
                if (failure.getCause() instanceof Error error) throw error;
                if (failure.getCause() instanceof Exception exception) throw exception;
                throw failure;
            }
            try (WaveyProducerFixture producer = new WaveyProducerFixture(Path.of(args[0]));
                    CapeRegistry registry = new CapeRegistry();
                    PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry, TextureRegistry.TextureKind.IMAGE,
                            com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
                java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(64, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(image, "PNG", png);
                cache.request("inspection", () -> CompletableFuture.completedFuture(Optional.of(png.toByteArray())), () -> {});
                TextureHandle inspected = cache.handle("inspection").orElseThrow();
                var previewToken = cache.capture("inspection");
                String world = CapeProjection.material(producer.self, "Self", true).capeLocation();
                var worldMaterial = CapeMaterialSnapshot.world(producer.self, "Self", true, world);
                check(worldMaterial != null && worldMaterial.validity().isValid(), "World positive control absent");
                TextureHandle worldHandle = new TextureHandle(world, 128, 64);
                TextureHandle skin = new TextureHandle("nclskins:preview_skin", 64, 64);
                State state = new State();
                PlayerWrapper entityless = new PlayerWrapper(state);
                PlayerWrapper actor = new PlayerWrapper(state) {
                    @Override public net.minecraft.resources.Identifier getCapeTexture() {
                        return state.skin.cape() == null ? null : state.skin.cape().texturePath();
                    }
                };
                Method configure = Class.forName("com.naocraftlab.skins.compat.client.identifier.submission.SubmissionPreviewRenderer")
                        .getDeclaredMethod("configure", AvatarRenderState.class, PreviewRequest.class);
                configure.setAccessible(true);
                try (WaveyCapesSupport support = WaveyCapesSupport.register()) {
                    for (boolean glint : new boolean[]{true, false}) {
                        state.nclskins$setCapeMaterial(worldMaterial);
                        PreviewAppearance appearance = new PreviewAppearance(skin, SkinModel.SLIM,
                                Optional.of(glint ? inspected : worldHandle), CapeMode.CAPE, OuterLayerVisibility.allVisible(),
                                true, glint, false, glint ? previewToken : TextureUseValidity.INVALID);
                        configure.invoke(null, state, new PreviewRequest(appearance, 0, 0, 100, 160, 0, 0, 1));
                        check(!support.shouldBeUsed(entityless), "Genuine entityless wrapper must retain existing fallback");
                        check(support.shouldBeUsed(actor) == glint, "Production preview publication borrowed world material");
                        check(state.skin.model().toString().equalsIgnoreCase("slim"), "Preview model reset");
                        if (glint) check(state.nclskins$capeMaterial().validity() == previewToken
                                && support.getCapePasses(actor).size() == 1, "Preview token/combined policy lost");
                    }
                    for (CapeMode mode : new CapeMode[]{CapeMode.OFF, CapeMode.ELYTRA}) {
                        state.nclskins$setCapeMaterial(worldMaterial);
                        PreviewAppearance appearance = new PreviewAppearance(skin, SkinModel.CLASSIC,
                                mode == CapeMode.OFF ? Optional.empty() : Optional.of(inspected), mode,
                                OuterLayerVisibility.allVisible(), true, true, false, previewToken);
                        configure.invoke(null, state, new PreviewRequest(appearance, 0, 0, 100, 160, 0, 0, 1));
                        check(!support.shouldBeUsed(actor) && state.nclskins$capeMaterial() == null,
                                "No-cape/elytra preview retained provider foil");
                        if (mode == CapeMode.ELYTRA) {
                            state.chestEquipment.set(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                            check(Boolean.TRUE.equals(state.chestEquipment.getClass().getMethod("hasFoil").invoke(state.chestEquipment))
                                            && actor.hasElytraEquipped() && !support.shouldBeUsed(actor),
                                    "Native elytra foil policy was changed");
                        }
                    }
                    check(producer.profileReads.get() == 1 && producer.imageReads.get() == 1, "Preview render caused world I/O");
                    cache.close();
                    check(!previewToken.isValid() && worldMaterial.validity().isValid(), "Preview retirement affected world ownership");
                }
            }
        }
        System.out.println("PREVIEW_PUBLICATION_PASS real cache/appearance -> actual production configure -> production support; synthetic installed-texture accessor, genuine visibility/elytra/state getters, genuine entityless fallback; worldless/false/same-world-location/OFF/elytra/native-foil, model retained; no GPU");
    }
    public static final class State extends AvatarRenderState implements CapeMaterialSnapshot.Carrier {
        private CapeMaterialSnapshot material;
        @Override public CapeMaterialSnapshot nclskins$capeMaterial() { return material; }
        @Override public void nclskins$setCapeMaterial(CapeMaterialSnapshot value) { material = value; }
    }
}
