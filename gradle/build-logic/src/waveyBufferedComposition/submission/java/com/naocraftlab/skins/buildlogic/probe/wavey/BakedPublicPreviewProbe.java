package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.client.PreviewRenderer.*;
import com.naocraftlab.skins.compat.client.identifier.foil.CapeTranslucentFoilMaterial;
import com.naocraftlab.skins.compat.client.identifier.submission.*;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import com.naocraftlab.skins.runtime.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;

public final class BakedPublicPreviewProbe {
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static void set(Class<?> owner, Object instance, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name); field.setAccessible(true); field.set(instance, value);
    }
    private static final class Buffers extends MultiBufferSource.BufferSource {
        final List<RenderType> acquired = new ArrayList<>();
        Buffers() { super(null, new LinkedHashMap<>()); }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer getBuffer(RenderType type) {
            acquired.add(type); return super.getBuffer(type);
        }
    }
    public static void runCase(String[] args) throws Exception {
        try (HeadlessCapeClient host = HeadlessCapeClient.open(false);
                WaveyProducerFixture producer = new WaveyProducerFixture(Path.of(args[0]));
                CapeRegistry registry = new CapeRegistry();
                PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry, TextureRegistry.TextureKind.IMAGE, DiagnosticSinks.discarding());
                SubmissionPreviewRenderer preview = new SubmissionPreviewRenderer(DiagnosticSinks.discarding());
                SimplePreviewRenderer thumbnails = new SimplePreviewRenderer()) {
            Minecraft minecraft = Minecraft.getInstance();
            check(minecraft.level == null && minecraft.player == null, "BAKED_NOT_WORLDLESS");
            var game = CapePublicationProbe.allocate(GameRenderer.class);
            set(GameRenderer.class, game, "lighting", CapePublicationProbe.allocate(Lighting.class));
            set(Minecraft.class, minecraft, "gameRenderer", game);
            cache.request("inspection", () -> CompletableFuture.completedFuture(Optional.of(producer.firstFramePng)), () -> {});
            var handle = cache.handle("inspection").orElseThrow();
            var token = cache.capture("inspection");
            int loads = registry.loads;
            GuiRenderState gui = new GuiRenderState();
            GuiGraphics graphics = CapePublicationProbe.allocate(GuiGraphics.class);
            set(GuiGraphics.class, graphics, "minecraft", minecraft);
            set(GuiGraphics.class, graphics, "pose", new Matrix3x2fStack(8));
            var scissorType = Class.forName("net.minecraft.client.gui.GuiGraphics$ScissorStack");
            var scissorConstructor = scissorType.getDeclaredConstructor(); scissorConstructor.setAccessible(true);
            set(GuiGraphics.class, graphics, "scissorStack", scissorConstructor.newInstance());
            set(GuiGraphics.class, graphics, "guiRenderState", gui);
            for (PreviewIntent intent : PreviewIntent.values()) for (CapeMode mode : CapeMode.values()) for (boolean glint : new boolean[]{true, false}) {
                gui.reset();
                PreviewAppearance appearance = CapePublicationProbe.appearance(handle, mode, glint, token);
                PreviewRenderer<GuiGraphics> publicPreview = preview;
                publicPreview.render(graphics, new PreviewRequest(appearance, 0, 0, 100, 160, 0, 0, 1, intent));
                BakedPreviewRenderState emitted = emitted(gui);
                check(emitted.capeMode() == mode, "BAKED_PUBLIC_ROLE_PUBLICATION");
                check(emitted.playerModel() != null && emitted.standaloneEquipment() == false, "BAKED_PUBLIC_MODEL");
                consume(emitted, mode == CapeMode.CAPE && glint);
                check(token.isValid() && registry.loads == loads, "BAKED_RENDER_CHANGED_CACHE_OWNER");
            }
            gui.reset();
            preview.render(graphics, new PreviewRequest(CapePublicationProbe.appearance(handle, CapeMode.CAPE, false, token), 0, 0, 100, 160, 0, 0, 1));
            try {
                consume(emitted(gui), true);
                throw new IllegalStateException("BAKED_NO_FOIL_SENSITIVITY_ACCEPTED");
            } catch (AssertionError expected) {
                check("BAKED_NATIVE_FOIL_MISSING_OR_LEAKED".equals(expected.getMessage()), "BAKED_SENSITIVITY_NOT_DOWNSTREAM");
                System.out.println("BAKED_EXPECTED_DOWNSTREAM_FOIL_RED false input consumed by real baked native path");
            }
            gui.reset();
            thumbnails.render(graphics, new BackEquipmentPreviewRenderer.Request(handle, BackEquipmentPreviewRenderer.Mode.CAPE, 0, 0, 40, 60, true));
            BakedPreviewRenderState thumbnail = emitted(gui);
            check(thumbnail.standaloneEquipment() && !thumbnail.capeGlint(), "BAKED_THUMBNAIL_PROVIDER_GLINT");
            consume(thumbnail, false);
            check(producer.profileReads.get() == 1 && producer.imageReads.get() == 1, "BAKED_PUBLIC_RENDER_SOURCE_IO");
            cache.close(); check(!token.isValid(), "BAKED_CACHE_NOT_RETIRED");
            gui.reset(); preview.close();
            preview.render(graphics, new PreviewRequest(CapePublicationProbe.appearance(handle, CapeMode.CAPE, true, token), 0, 0, 100, 160, 0, 0, 1));
            var afterClose = new ArrayList<PictureInPictureRenderState>(); gui.forEachPictureInPicture(afterClose::add);
            check(afterClose.isEmpty(), "BAKED_CLOSED_PUBLIC_PREVIEW_PUBLISHED");
        }
        System.out.println("PUBLIC_WORLDLESS_BAKED_PASS public PreviewRenderer -> actual session fallback/Simple/BakedPreviewSubmission/normal GUI Mixin -> exact emitted state -> actual TargetRenderer/CapeFoilBuffer/native getBuffer/endBatch; all intents/roles/false/thumbnail/close; CPU geometry+lighting sinks only; NOT entityless Wavey");
    }
    private static BakedPreviewRenderState emitted(GuiRenderState gui) {
        var values = new ArrayList<PictureInPictureRenderState>(); gui.forEachPictureInPicture(values::add);
        check(values.size() == 1 && values.get(0) instanceof BakedPreviewRenderState, "BAKED_PUBLIC_STATE_NOT_EMITTED");
        return (BakedPreviewRenderState) values.get(0);
    }
    private static void consume(BakedPreviewRenderState emitted, boolean foil) throws Exception {
        Buffers buffers = new Buffers();
        Class<?> target = Class.forName("com.naocraftlab.skins.compat.client.identifier.submission.BakedPreviewRenderer$TargetRenderer");
        Object nativeConsumer = CapePublicationProbe.allocate(target);
        set(net.minecraft.client.gui.render.pip.PictureInPictureRenderer.class, nativeConsumer, "bufferSource", buffers);
        var render = target.getDeclaredMethod("renderToTexture", BakedPreviewRenderState.class, PoseStack.class); render.setAccessible(true);
        int draws = BufferedCapeHost.draws, closes = BufferedCapeHost.closes;
        render.invoke(nativeConsumer, emitted, new PoseStack());
        Identifier texture = emitted.attachmentTexture();
        RenderType ownFoil = texture == null ? null : CapeTranslucentFoilMaterial.forTexture(texture);
        check(buffers.acquired.stream().filter(t -> t == ownFoil).count() == (foil ? 1 : 0), "BAKED_NATIVE_FOIL_MISSING_OR_LEAKED");
        int expected = (emitted.attachmentModel() == null ? 0 : 1) + (emitted.standaloneEquipment() ? 0 : 1);
        check(BufferedCapeHost.draws - draws == expected && BufferedCapeHost.closes - closes == expected, "BAKED_NATIVE_DRAIN_NOT_REACHED");
        BufferedCapeHost.verifyClosed();
    }
}
