package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.blaze3d.platform.Lighting;
import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.client.PreviewRenderer.*;
import com.naocraftlab.skins.compat.client.identifier.extraction.*;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import com.naocraftlab.skins.runtime.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.joml.Matrix3x2fStack;

public final class ExtractionBakedPreviewProbe {
    public static void main(String[] args) throws Exception {
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        runCase(args);
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static void set(Class<?> owner, Object instance, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name); field.setAccessible(true); field.set(instance, value);
    }
    public static void runCase(String[] args) throws Exception {
        try (HeadlessCapeClient host = HeadlessCapeClient.open(true);
                WaveyProducerFixture producer = new WaveyProducerFixture(Path.of(args[0]));
                CapeRegistry registry = new CapeRegistry();
                PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry, TextureRegistry.TextureKind.IMAGE, DiagnosticSinks.discarding());
                AvatarRenderStatePreviewRenderer preview = new AvatarRenderStatePreviewRenderer(DiagnosticSinks.discarding())) {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.player = CapePublicationProbe.allocate(net.minecraft.client.player.LocalPlayer.class);
            set(net.minecraft.world.entity.Entity.class, minecraft.player, "uuid", producer.self);
            var game = CapePublicationProbe.allocate(GameRenderer.class);
            set(GameRenderer.class, game, "lighting", CapePublicationProbe.allocate(Lighting.class));
            set(Minecraft.class, minecraft, "gameRenderer", game);
            cache.request("loser", () -> CompletableFuture.completedFuture(Optional.of(producer.firstFramePng)), () -> {});
            var handle = cache.handle("loser").orElseThrow(); var token = cache.capture("loser"); int loads = registry.loads;
            GuiRenderState gui = new GuiRenderState();
            GuiGraphicsExtractor graphics = CapePublicationProbe.allocate(GuiGraphicsExtractor.class);
            set(GuiGraphicsExtractor.class, graphics, "minecraft", minecraft);
            set(GuiGraphicsExtractor.class, graphics, "pose", new Matrix3x2fStack(8));
            set(GuiGraphicsExtractor.class, graphics, "scissorStack", BakedGuiInputs.scissorStack());
            set(GuiGraphicsExtractor.class, graphics, "guiRenderState", gui);
            for (boolean world : new boolean[]{true, false}) {
                var level = minecraft.level; if (!world) minecraft.level = null;
                for (PreviewIntent intent : PreviewIntent.values()) {
                    if (world && intent == PreviewIntent.EDITOR_DRAFT) continue;
                    for (CapeMode mode : CapeMode.values()) for (boolean glint : new boolean[]{true, false}) {
                        gui.reset();
                        PreviewAppearance appearance = CapePublicationProbe.appearance(handle, mode, glint, token);
                        preview.render(graphics, new PreviewRequest(appearance, 0, 0, 100, 160, 0, 0, 1, intent));
                        NclBakedPlayerRenderState emitted = emitted(gui);
                        check(emitted.capeMode() == mode && !emitted.standaloneEquipment(), "EXTRACTION_BAKED_PUBLIC_ROLE");
                        System.out.println("EXTRACTION_BAKED_CASE world=" + world + " intent=" + intent + " mode=" + mode
                                + " foil=" + glint + " standalone=" + emitted.standaloneEquipment()
                                + " attachment=" + (emitted.attachmentPlayerModel() != null));
                        ExtractionBakedConsumer.consume(emitted, mode == CapeMode.CAPE && glint);
                        check(token.isValid() && registry.loads == loads, "EXTRACTION_BAKED_CACHE_OWNER_IO");
                    }
                }
                gui.reset();
                preview.render(graphics, new PreviewRequest(CapePublicationProbe.appearance(handle, CapeMode.CAPE, false, token),
                        0, 0, 100, 160, 0, 0, 1, PreviewIntent.CURRENT_APPEARANCE));
                try {
                    ExtractionBakedConsumer.consume(emitted(gui), true);
                    throw new IllegalStateException("EXTRACTION_BAKED_FALSE_SENSITIVITY_ACCEPTED");
                } catch (AssertionError expected) {
                    check("EXTRACTION_BAKED_NATIVE_FOIL_MISSING_OR_LEAKED".equals(expected.getMessage()), "EXTRACTION_BAKED_SENSITIVITY_NOT_DOWNSTREAM");
                }
                var baked = AvatarRenderStatePreviewRenderer.class.getDeclaredField("bakedRenderer"); baked.setAccessible(true);
                gui.reset();
                @SuppressWarnings("unchecked") BackEquipmentPreviewRenderer<GuiGraphicsExtractor> thumbnails = (BackEquipmentPreviewRenderer<GuiGraphicsExtractor>) baked.get(preview);
                thumbnails.render(graphics, new BackEquipmentPreviewRenderer.Request(handle, BackEquipmentPreviewRenderer.Mode.CAPE, 0, 0, 40, 60, true));
                NclBakedPlayerRenderState thumbnail = emitted(gui);
                check(thumbnail.standaloneEquipment() && !thumbnail.capeGlint(), "EXTRACTION_BAKED_THUMBNAIL_GLINT");
                ExtractionBakedConsumer.consume(thumbnail, false);
                minecraft.level = level;
            }
            check(producer.profileReads.get() == 1 && producer.imageReads.get() == 1, "EXTRACTION_BAKED_SOURCE_IO");
            cache.close(); check(!token.isValid(), "EXTRACTION_BAKED_CACHE_RETIREMENT");
            preview.close(); gui.reset();
            preview.render(graphics, new PreviewRequest(CapePublicationProbe.appearance(handle, CapeMode.CAPE, true, token),
                    0, 0, 100, 160, 0, 0, 1, PreviewIntent.CURRENT_APPEARANCE));
            var after = new ArrayList<PictureInPictureRenderState>(); gui.forEachPictureInPicture(after::add);
            check(after.isEmpty(), "EXTRACTION_BAKED_CLOSED_PUBLICATION");
        }
        System.out.println("EXTRACTION_BAKED_PUBLIC_PASS actual public session CURRENT/ASSET in-world+worldless/editor worldless -> normal GUI Mixin -> same emitted NclBakedPlayerRenderState -> actual TargetRenderer/native consumer+drain; false/roles/thumbnail/source/retirement; not entityless Wavey");
    }
    private static NclBakedPlayerRenderState emitted(GuiRenderState gui) {
        var values = new ArrayList<PictureInPictureRenderState>(); gui.forEachPictureInPicture(values::add);
        check(values.size() == 1 && values.get(0) instanceof NclBakedPlayerRenderState, "EXTRACTION_BAKED_PUBLIC_STATE_MISSING");
        return (NclBakedPlayerRenderState) values.get(0);
    }
}
