package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.google.common.collect.ImmutableList;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.client.PreviewRenderer.*;
import com.naocraftlab.skins.compat.client.cape.CapeTranslucentFoilMaterial;
import com.naocraftlab.skins.compat.waveycapes.WaveyCapesSupport;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import com.naocraftlab.skins.runtime.*;
import dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper;
import dev.tr7zw.waveycapes.render.CapeNodeCollector;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.*;
import net.minecraft.client.renderer.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.phys.*;
import static com.naocraftlab.skins.buildlogic.probe.wavey.ImmediateCapeHost.*;

public final class ImmediatePublicPreviewProbe {
    private static LivingEntity observedActor;
    private static String observedTexture;
    private static Boolean observedGlint;
    private static TextureUseValidity observedValidity;
    private static boolean observedSupport;
    private static int terminalCalls;

    public static void main(String[] args) throws Exception {
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        var previous = dev.tr7zw.waveycapes.versionless.ModBase.config;
        try {
            dev.tr7zw.waveycapes.versionless.ModBase.config = new dev.tr7zw.waveycapes.versionless.config.Config();
            runCase(args);
        } finally { dev.tr7zw.waveycapes.versionless.ModBase.config = previous; }
    }
    private static final class Local extends LocalPlayer {
        private Local() { super(null, null, null, null, null, false, false); }
    }
    private static void resetCase() {
        reset(); observedActor = null; observedTexture = null; observedGlint = null;
        observedValidity = null; observedSupport = false; terminalCalls = 0;
    }
    private static void foil(boolean expected) {
        long count = drawn.stream().filter(type -> type == RenderType.entityGlintDirect() || type == RenderType.entityGlint()
                || type instanceof CapeTranslucentFoilMaterial).count();
        check(count == (expected ? 1 : 0), "LEGACY_PUBLIC_NATIVE_FOIL_MISSING_OR_LEAKED");
    }
    public static void initialize(AbstractClientPlayer actor, ClientLevel level, GameProfile profile) {
        field(actor, Entity.class, "uuid", profile.getId());
        field(actor, Entity.class, "firstTick", true);
        field(actor, Player.class, "gameProfile", profile);
        field(actor, Entity.class, "level", level);
        field(actor, Entity.class, "position", Vec3.ZERO);
        field(actor, Entity.class, "deltaMovement", Vec3.ZERO);
        field(actor, Entity.class, "blockPosition", net.minecraft.core.BlockPos.ZERO);
        field(actor, Entity.class, "chunkPosition", new net.minecraft.world.level.ChunkPos(0, 0));
        field(actor, Entity.class, "bb", new AABB(0, 0, 0, 0.6, 1.8, 0.6));
        field(actor, Entity.class, "dimensions", EntityDimensions.scalable(0.6f, 1.8f));
        field(actor, Entity.class, "levelCallback", net.minecraft.world.level.entity.EntityInLevelCallback.NULL);
        field(actor, Entity.class, "passengers", ImmutableList.of());
        field(actor, Entity.class, "random", net.minecraft.util.RandomSource.create(1));
        field(actor, Player.class, "inventory", new Inventory(actor));
    }
    public static void guiTerminal(GuiGraphics graphics, LivingEntity passedActor) {
        terminalCalls++; observedActor = passedActor;
        check(passedActor instanceof AbstractClientPlayer, "LEGACY_PUBLIC_ACTOR_TYPE");
        PlayerWrapper wrapper = new PlayerWrapper((AbstractClientPlayer) passedActor);
        observedTexture = wrapper.getCapeTexture() == null ? null : wrapper.getCapeTexture().toString();
        String key = observedTexture == null ? "nclskins:absent" : observedTexture;
        observedGlint = ExactLocalPlayerScope.capeGlint(passedActor, key);
        observedValidity = ExactLocalPlayerScope.capeValidity(passedActor, key);
        check(observedGlint != null && observedValidity != null, "LEGACY_PUBLIC_SCOPE_NOT_ACTIVE");
        check(ExactLocalPlayerScope.capeGlint(Minecraft.getInstance().player, key) == null, "LEGACY_PUBLIC_SCOPE_WRONG_ACTOR");
        check(!passedActor.isInvisible() && passedActor.getPose() == Pose.STANDING
                && passedActor.getYRot() == 180.0f, "LEGACY_PUBLIC_CONFIGURE_NOT_REACHED");
        observedSupport = dev.tr7zw.waveycapes.support.SupportManager.getSupportedMods().stream()
                .filter(s -> s instanceof WaveyCapesSupport).anyMatch(s -> s.shouldBeUsed(wrapper));
        expectedActor = wrapper; expectedPose = graphics.pose();
        new CapeNodeCollector().submitCape(graphics.bufferSource(), wrapper, expectedPose, 15728880, 1.0f);
    }
    private static void actor(Object expected) {
        check(observedActor == expected, "LEGACY_PUBLIC_ACTOR_CHANGED");
    }
    public static void runCase(String[] args) throws Exception {
        int liveCases = 0, fallbackCases = 0, inactiveRejections = 0;
        Minecraft previous = Minecraft.getInstance();
        var previousUser = previous == null ? null : previous.getUser();
        try (HeadlessCapeClient host = HeadlessCapeClient.open(true);
                WaveyProducerFixture producer = new WaveyProducerFixture(Path.of(args[0]));
                CapeRegistry registry = new CapeRegistry();
                PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry, TextureRegistry.TextureKind.IMAGE, DiagnosticSinks.discarding());
                WaveyCapesSupport support = WaveyCapesSupport.register()) {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.player = allocate(Local.class);
            field(minecraft.player, Entity.class, "uuid", producer.self);
            field(minecraft, Minecraft.class, "user", ImmediateAppearanceInputs.currentUser(producer.self));
            check(minecraft != previous && producer.self.equals(minecraft.getUser().getProfileId()), "CURRENT_SUBJECT_IDENTITY");
            check(minecraft.player.getUUID().equals(minecraft.getUser().getProfileId()), "ACTOR_SUBJECT_IDENTITY");
            cache.request("inspection", () -> CompletableFuture.completedFuture(Optional.of(producer.firstFramePng)), () -> {});
            var cape = cache.handle("inspection").orElseThrow(); var token = cache.capture("inspection");
            PreviewRenderer<GuiGraphics> preview = ImmediatePreviewInputs.preview(minecraft);
            Class<?> owner = preview.getClass();
            AbstractClientPlayer actor = ImmediatePreviewInputs.actor(minecraft.level, (GameProfile) field(preview, owner, "previewProfile"));
            field(preview, owner, "previewPlayer", actor); field(preview, owner, "previewLevel", minecraft.level);
            GuiGraphics graphics = allocate(GuiGraphics.class);
            field(graphics, GuiGraphics.class, "minecraft", minecraft); field(graphics, GuiGraphics.class, "pose", new PoseStack());
            ImmediateBuffers.verifyFixedFoil();
            int loads = registry.loads;
            try {
                for (boolean world : new boolean[]{true, false}) {
                    ClientLevel level = minecraft.level;
                    if (!world) { minecraft.level = null; CapeTranslucentFoilMaterial.shaderLoaded(allocate(ShaderInstance.class)); }
                    for (PreviewIntent intent : PreviewIntent.values()) for (CapeMode mode : CapeMode.values())
                            for (boolean present : mode == CapeMode.OFF ? new boolean[]{true, false} : new boolean[]{true})
                                    for (boolean glint : new boolean[]{true, false}) {
                        resetCase(); check(expectedActor == null && lastReturned == null && observedActor == null, "LEGACY_CASE_NOT_RESET");
                        var buffers = ImmediateBuffers.create(); field(graphics, GuiGraphics.class, "bufferSource", buffers);
                        var appearance = new PreviewAppearance(new TextureRegistry.TextureHandle("nclskins:synthetic_skin", 64, 64), SkinModel.SLIM,
                                present ? Optional.of(cape) : Optional.empty(), mode, OuterLayerVisibility.allVisible(), false, glint, false, token);
                        preview.render(graphics, new PreviewRequest(appearance, 0, 0, 100, 160, 0, 0, 1, intent));
                        buffers.endBatch();
                        boolean live = world && intent == PreviewIntent.EDITOR_DRAFT;
                        boolean hasCape = present && mode == CapeMode.CAPE;
                        if (live) {
                            check(terminalCalls == 1 && expectedActor != null, "LEGACY_FINAL_GUI_CONSUMER_NOT_REACHED"); actor(actor);
                            check(Objects.equals(observedTexture, hasCape ? cape.location() : null)
                                    && observedGlint == (hasCape && glint), "LEGACY_PUBLIC_SOURCE_ROLE_TEXTURE");
                            check(observedValidity == (hasCape && glint ? token : TextureUseValidity.INVALID)
                                    && observedSupport == (hasCape && glint), "LEGACY_PUBLIC_CACHE_OWNED_MATERIAL");
                            check(geometries == (hasCape ? glint ? 2 : 1 : 0)
                                    && drawn.size() == (hasCape ? glint ? 2 : 1 : 0), "LEGACY_PUBLIC_CAPE_PASS_COUNT");
                            if (hasCape) { ImmediateBuffers.verifyInactive(lastReturned); inactiveRejections++; }
                            liveCases++;
                        } else {
                            check(terminalCalls == 0 && expectedActor == null && !drawn.isEmpty(), "LEGACY_BAKED_FALLBACK_NOT_CONSUMED");
                            fallbackCases++;
                        }
                        foil(hasCape && glint);
                        check(ImmediateBuffers.pending() == 0, "LEGACY_PUBLIC_NATIVE_DRAIN_MISSING");
                        check(ExactLocalPlayerScope.capeGlint(actor, cape.location()) == null && token.isValid()
                                && registry.loads == loads, "LEGACY_PUBLIC_SCOPE_OR_OWNER_LEAK");
                    }
                    minecraft.level = level;
                }
                producer.glintOnlyRefresh(false);
                check(!CapeProjection.material(producer.self, "Self", true).capeGlint(), "LEGACY_INSPECTED_LOSER_PREREQUISITE");
                resetCase(); var inspectedBuffers = ImmediateBuffers.create(); field(graphics, GuiGraphics.class, "bufferSource", inspectedBuffers);
                var inspected = new PreviewAppearance(new TextureRegistry.TextureHandle("nclskins:synthetic_skin", 64, 64), SkinModel.SLIM,
                        Optional.of(cape), CapeMode.CAPE, OuterLayerVisibility.allVisible(), false, true, false, token);
                preview.render(graphics, new PreviewRequest(inspected, 0, 0, 100, 160, 0, 0, 1, PreviewIntent.EDITOR_DRAFT));
                inspectedBuffers.endBatch(); actor(actor); foil(true);
                check(terminalCalls == 1 && observedSupport && observedValidity == token && geometries == 2,
                        "LEGACY_INSPECTION_BORROWED_WORLD_MATERIAL");
                producer.glintOnlyRefresh(true);
                int profiles = producer.profileReads.get(), images = producer.imageReads.get();
                resetCase(); var plain = ImmediateBuffers.create(); field(graphics, GuiGraphics.class, "bufferSource", plain);
                var suppressed = new PreviewAppearance(new TextureRegistry.TextureHandle("nclskins:synthetic_skin", 64, 64), SkinModel.SLIM,
                        Optional.of(cape), CapeMode.CAPE, OuterLayerVisibility.allVisible(), false, false, false, token);
                var request = new PreviewRequest(suppressed, 0, 0, 100, 160, 0, 0, 1, PreviewIntent.EDITOR_DRAFT);
                preview.render(graphics, request); plain.endBatch();
                check(terminalCalls == 1 && observedActor == actor && geometries > 0 && !drawn.isEmpty(), "LEGACY_FALSE_RAW_PREDECESSOR_MISSING");
                try { foil(true); throw new IllegalStateException("LEGACY_FALSE_SENSITIVITY_ACCEPTED"); }
                catch (AssertionError expected) { check("LEGACY_PUBLIC_NATIVE_FOIL_MISSING_OR_LEAKED".equals(expected.getMessage()), "LEGACY_FALSE_SENSITIVITY_NOT_DOWNSTREAM"); }
                try { actor(new Object()); throw new IllegalStateException("LEGACY_RAW_ACTOR_SENSITIVITY_ACCEPTED"); }
                catch (AssertionError expected) { check("LEGACY_PUBLIC_ACTOR_CHANGED".equals(expected.getMessage()), "LEGACY_RAW_ACTOR_SENSITIVITY_NOT_DOWNSTREAM"); }
                resetCase(); var buffers = ImmediateBuffers.create(); field(graphics, GuiGraphics.class, "bufferSource", buffers);
                ImmediatePreviewInputs.thumbnails(minecraft).render(graphics, new BackEquipmentPreviewRenderer.Request(cape,
                        BackEquipmentPreviewRenderer.Mode.CAPE, 0, 0, 40, 60, true));
                check(drawn.stream().noneMatch(type -> type == RenderType.entityGlintDirect() || type instanceof CapeTranslucentFoilMaterial), "LEGACY_THUMBNAIL_GLINT");
                check(producer.profileReads.get() == profiles && producer.imageReads.get() == images, "LEGACY_PUBLIC_SOURCE_IO");
                cache.close(); check(!token.isValid(), "LEGACY_PUBLIC_CACHE_RETIREMENT");
                resetCase(); var retiredBuffers = ImmediateBuffers.create(); field(graphics, GuiGraphics.class, "bufferSource", retiredBuffers);
                preview.render(graphics, new PreviewRequest(inspected, 0, 0, 100, 160, 0, 0, 1, PreviewIntent.EDITOR_DRAFT));
                retiredBuffers.endBatch(); actor(actor); foil(false);
                check(terminalCalls == 1 && observedValidity == token && !observedSupport && geometries == 1
                        && ImmediateBuffers.pending() == 0 && registry.loads == loads
                        && ExactLocalPlayerScope.capeGlint(actor, cape.location()) == null,
                        "LEGACY_RETIRED_CACHE_BORROWED_WORLD_MATERIAL");
            } finally {
                ((AutoCloseable) preview).close(); resetCase();
                check(expectedActor == null && lastReturned == null && consumers.isEmpty()
                        && observedActor == null && observedValidity == null && terminalCalls == 0, "LEGACY_FINAL_REFERENCE_RESET_MISSING");
            }
        } finally {
            check(Minecraft.getInstance() == previous && (previous == null || previous.getUser() == previousUser), "PREVIOUS_SUBJECT_RESTORED");
        }
        System.out.println("LEGACY_PUBLIC_PREVIEW_PASS liveCases=" + liveCases + "; fallbackCases=" + fallbackCases
                + "; inactiveRejections=" + inactiveRejections + "; scope/cache/falseSensitivity/wrongActorSensitivity=PASS; public render/session/configure -> final InventoryScreen GUI SUBSTITUTED -> same actual actor/scope/genuine collector/native cape drain; actual own baked fallback CURRENT/ASSET/menu; warm raw actor fixture; full InventoryScreen/dispatcher/body/nameplate/layer UI NOT_EXECUTED; GPU NOT_RUN");
    }
}
