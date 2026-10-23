package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.client.PreviewRenderer.*;
import com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass;
import com.naocraftlab.skins.compat.client.identifier.submission.*;
import com.naocraftlab.skins.compat.waveycapes.WaveyCapesSupport;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import com.naocraftlab.skins.runtime.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.*;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.entity.player.PlayerModelType;
import org.joml.Matrix3x2fStack;

public final class LivePublicPreviewProbe {
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static Object value(LivePreviewRenderState state, String name) throws Exception {
        var accessor = LivePreviewRenderState.class.getDeclaredMethod(name); accessor.setAccessible(true); return accessor.invoke(state);
    }
    private static final class Buffers extends MultiBufferSource.BufferSource {
        final List<RenderType> acquired = new ArrayList<>();
        Buffers() { super(null, new LinkedHashMap<>()); }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer getBuffer(RenderType type) {
            acquired.add(type); return super.getBuffer(type);
        }
    }
    private static final class CollectorLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
        private final WaveyCapesSupport support;
        private AvatarRenderState consumed;
        private int calls;
        CollectorLayer(AvatarRenderer<?> renderer, WaveyCapesSupport support) { super(renderer); this.support = support; }
        @Override public void submit(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state, float yaw, float pitch) {
            consumed = state; calls++;
            check(state == CapePublicationProbe.lastRawState(), "LIVE_SUBMITTED_STATE_IDENTITY");
            if (!state.showCape) return;
            var actor = CapePublicationProbe.wrapper(state);
            new dev.tr7zw.waveycapes.render.CapeNodeCollector().submitCape(collector, actor, pose, light, 0.25F);
        }
    }
    public static void runCase(String[] args) throws Exception {
        try (HeadlessCapeClient host = HeadlessCapeClient.open(true);
                WaveyProducerFixture producer = new WaveyProducerFixture(Path.of(args[0]));
                CapeRegistry registry = new CapeRegistry();
                PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry, TextureRegistry.TextureKind.IMAGE, DiagnosticSinks.discarding());
                WaveyCapesSupport support = WaveyCapesSupport.register();
                SubmissionPreviewRenderer preview = new SubmissionPreviewRenderer(DiagnosticSinks.discarding())) {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.player = CapePublicationProbe.allocate(LocalPlayer.class);
            SubmissionRawInputs.set(net.minecraft.world.entity.Entity.class, minecraft.player, "uuid", producer.self);
            SubmissionRawInputs.set(Minecraft.class, minecraft, "user", new User("Self", producer.self, "", Optional.empty(), Optional.empty()));
            SubmissionRawInputs.set(Minecraft.class, minecraft, "deltaTracker", new DeltaTracker.Timer(20, 0, value -> value));
            AvatarRenderer<?> renderer = CapePublicationProbe.allocate(AvatarRenderer.class);
            PlayerModel model = new PlayerModel(LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, true), 64, 64).bakeRoot(), true);
            SubmissionRawInputs.set(LivingEntityRenderer.class, renderer, "model", model);
            CollectorLayer layer = new CollectorLayer(renderer, support);
            SubmissionRawInputs.set(LivingEntityRenderer.class, renderer, "layers", List.of(layer));
            EntityRenderDispatcher dispatcher = CapePublicationProbe.allocate(EntityRenderDispatcher.class);
            SubmissionRawInputs.set(EntityRenderDispatcher.class, dispatcher, "playerRenderers", Map.of(PlayerModelType.SLIM, renderer, PlayerModelType.WIDE, renderer));
            SubmissionRawInputs.set(Minecraft.class, minecraft, "entityRenderDispatcher", dispatcher);
            GameRenderer game = CapePublicationProbe.allocate(GameRenderer.class);
            SubmissionRawInputs.set(GameRenderer.class, game, "lighting", CapePublicationProbe.allocate(Lighting.class));
            SubmissionRawInputs.set(Minecraft.class, minecraft, "gameRenderer", game);
            GuiRenderState gui = new GuiRenderState();
            GuiGraphics graphics = CapePublicationProbe.allocate(GuiGraphics.class);
            SubmissionRawInputs.set(GuiGraphics.class, graphics, "minecraft", minecraft);
            SubmissionRawInputs.set(GuiGraphics.class, graphics, "pose", new Matrix3x2fStack(8));
            Class<?> scissors = Class.forName("net.minecraft.client.gui.GuiGraphics$ScissorStack");
            var constructor = scissors.getDeclaredConstructor(); constructor.setAccessible(true);
            SubmissionRawInputs.set(GuiGraphics.class, graphics, "scissorStack", constructor.newInstance());
            SubmissionRawInputs.set(GuiGraphics.class, graphics, "guiRenderState", gui);
            cache.request("loser", () -> CompletableFuture.completedFuture(Optional.of(producer.firstFramePng)), () -> {});
            var handle = cache.handle("loser").orElseThrow(); var token = cache.capture("loser");
            int loads = registry.loads;
            String world = CapeProjection.material(producer.self, "Self", true).capeLocation();
            var worldToken = CapeProjection.captureCapeTexture(producer.self, "Self", true, world);
            check(token != worldToken && worldToken.isValid(), "LIVE_CACHE_WORLD_INCARNATION_CONFLATED");
            List<AvatarRenderState> positiveStates = new ArrayList<>();
            for (PreviewIntent intent : PreviewIntent.values()) for (CapeMode mode : CapeMode.values()) for (boolean foil : new boolean[]{true, false}) {
                gui.reset();
                var appearance = CapePublicationProbe.appearance(handle, mode, foil, token);
                PreviewRequest request = new PreviewRequest(appearance, 0, 0, 100, 160, 0, 0, 1, intent);
                PreviewRenderer<GuiGraphics> publicPreview = preview; publicPreview.render(graphics, request);
                List<PictureInPictureRenderState> states = new ArrayList<>(); gui.forEachPictureInPicture(states::add);
                if (intent != PreviewIntent.EDITOR_DRAFT) {
                    check(states.size() == 1 && states.getFirst() instanceof BakedPreviewRenderState, "NON_DRAFT_LIVE_SESSION_LEAK");
                    continue;
                }
                check(states.size() == 1 && states.getFirst() instanceof LivePreviewRenderState, "LIVE_PUBLIC_HANDOFF_MISSING");
                LivePreviewRenderState emitted = (LivePreviewRenderState) states.getFirst();
                check(value(emitted, "request") == request && value(emitted, "previewPlayer") != minecraft.player, "LIVE_REQUEST_ACTOR_IDENTITY");
                Buffers buffers = new Buffers();
                FeatureRenderDispatcher features = new FeatureRenderDispatcher(new SubmitNodeStorage(), null, buffers, null, null, buffers, null);
                SubmissionRawInputs.set(GameRenderer.class, game, "featureRenderDispatcher", features);
                LivePreviewRenderer consumer = CapePublicationProbe.allocate(LivePreviewRenderer.class);
                var render = LivePreviewRenderer.class.getDeclaredMethod("renderToTexture", LivePreviewRenderState.class, PoseStack.class); render.setAccessible(true);
                int calls = layer.calls, geometry = BufferedCapeHost.geometry;
                render.invoke(consumer, emitted, new PoseStack());
                buffers.endBatch();
                check(layer.calls == calls + 1 && layer.consumed == CapePublicationProbe.lastRawState()
                        && value(emitted, "previewPlayer") == CapePublicationProbe.lastRawActor(), "LIVE_EXTRACT_CONFIGURE_SUBMIT_NOT_REACHED");
                var state = layer.consumed;
                boolean own = mode == CapeMode.CAPE && foil;
                check(support.shouldBeUsed(CapePublicationProbe.wrapper(state)) == own, "LIVE_PREVIEW_BORROWED_WORLD_MATERIAL");
                check(buffers.acquired.stream().filter(t -> CapturedCapePass.of(t) != null).count() == (own ? 2 : 0), "LIVE_NATIVE_FOIL_MISSING_OR_LEAKED");
                if (own) check(CapePublicationProbe.material(state).validity() == token && BufferedCapeHost.geometry - geometry == 2,
                        "LIVE_CACHE_CAPTURE_OR_GENUINE_CONSUMER_LOST");
                if (own) positiveStates.add(state);
                check(state.skin.model() == PlayerModelType.SLIM && token.isValid() && registry.loads == loads, "LIVE_MODEL_CACHE_CHANGED");
                check(ExactLocalPlayerScope.capeGlint(minecraft.player, handle.location()) == null, "LIVE_SCOPE_LEAK");
                BufferedCapeHost.verifyClosed();
            }
            var readiness = NativePlayerSkinLifecycle.pending("nclskins:preview_skin");
            try {
                gui.reset(); preview.render(graphics, new PreviewRequest(CapePublicationProbe.appearance(handle, CapeMode.CAPE, true, token), 0, 0, 100, 160, 0, 0, 1, PreviewIntent.EDITOR_DRAFT));
                List<PictureInPictureRenderState> states = new ArrayList<>(); gui.forEachPictureInPicture(states::add);
                check(states.size() == 1 && states.getFirst() instanceof BakedPreviewRenderState, "LIVE_PENDING_SKIN_BYPASSED_READINESS");
            } finally { readiness.retire(); }
            cache.close(); check(!token.isValid() && worldToken.isValid(), "LIVE_CACHE_RETIREMENT_AFFECTED_WORLD");
            check(!positiveStates.isEmpty() && positiveStates.stream().noneMatch(state -> support.shouldBeUsed(CapePublicationProbe.wrapper(state))), "LIVE_RETIRED_PREVIEW_REMAINED_ADMITTED");
            positiveStates.clear();
            check(producer.profileReads.get() == 1 && producer.imageReads.get() == 1, "LIVE_PUBLIC_RENDER_SOURCE_IO");
            layer.consumed = null;
            preview.close(); gui.reset();
            preview.render(graphics, new PreviewRequest(CapePublicationProbe.appearance(handle, CapeMode.CAPE, true, token), 0, 0, 100, 160, 0, 0, 1));
            List<PictureInPictureRenderState> closed = new ArrayList<>(); gui.forEachPictureInPicture(closed::add);
            check(closed.isEmpty(), "LIVE_CLOSED_PUBLIC_PREVIEW_PUBLISHED");
        }
        System.out.println("PUBLIC_LIVE_PREVIEW_PASS public render/session/readiness -> normal GUI Mixin/same LivePreviewRenderState -> real LivePreviewRenderer/extract/configure/native submit -> same AvatarRenderState/native layer loop/genuine collector/FeatureRenderDispatcher/native buffer drain; raw inputs+CPU geometry+lighting sinks; test layer bridge, not Wavey lifecycle/simulation");
    }
}
