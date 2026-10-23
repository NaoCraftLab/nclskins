package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.blaze3d.vertex.*;
import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.compat.client.cape.*;
import com.naocraftlab.skins.compat.waveycapes.*;
import com.naocraftlab.skins.runtime.*;
import dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper;
import dev.tr7zw.waveycapes.render.*;
import dev.tr7zw.waveycapes.support.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import static com.naocraftlab.skins.buildlogic.probe.wavey.ImmediateCapeHost.*;

public final class ImmediateCapeProbe {
    public static void main(String[] args) throws Exception {
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        runCase(args);
    }
    public static void runCase(String[] args) throws Exception {
        runCase(args, null);
    }
    static void runCase(String[] args, WaveyCapesSupport installedSupport) throws Exception {
        String context = args[1], scenario = args[2];
        boolean world = !context.equals("menu"), inspection = context.equals("inspection");
        if (!Boolean.getBoolean("nclskins.wavey.loaderBatch")) {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
        }
        Minecraft previous = Minecraft.getInstance();
        var previousUser = previous == null ? null : previous.getUser();
        try (HeadlessCapeClient client = HeadlessCapeClient.open(world);
                WaveyProducerFixture producer = new WaveyProducerFixture(Path.of(args[0]))) {
            Minecraft minecraft = Minecraft.getInstance();
            field(minecraft, Minecraft.class, "user", ImmediateAppearanceInputs.currentUser(producer.self));
            check(minecraft != previous && producer.self.equals(minecraft.getUser().getProfileId()), "CURRENT_SUBJECT_IDENTITY");
            if (!world) CapeTranslucentFoilMaterial.shaderLoaded(allocate(ShaderInstance.class));
            ResourceLocation installed = ResourceLocation.tryParse(CapeProjection.material(producer.self, "Self", true).capeLocation());
            TextureUseValidity token = CapeProjection.captureCapeTexture(producer.self, "Self", true, installed.toString());
            Actor actor = allocate(Actor.class); actor.identity = producer.self;
            check(actor.getUUID().equals(minecraft.getUser().getProfileId()), "ACTOR_SUBJECT_IDENTITY");
            Wrapper wrapper = new Wrapper(actor); wrapper.texture = installed;
            if (Boolean.getBoolean("nclskins.wavey.loaderBatch") && !inspection) {
                wrapper.info = ImmediateAppearanceInputs.create(producer.self);
                check(installed.equals(wrapper.getCapeTexture()), "WORLD_NATIVE_LOOKUP_MISSING");
            }
            expectedActor = wrapper; expectedPose = new PoseStack();
            var appearance = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("nclskins:skin", 64, 64),
                    SkinModel.CLASSIC, Optional.of(new TextureRegistry.TextureHandle(installed.toString(), 128, 64)),
                    PreviewRenderer.CapeMode.CAPE, OuterLayerVisibility.allVisible(), false, true, false, token);
            Set<ModSupport> before = new HashSet<>(SupportManager.getSupportedMods());
            boolean withoutSupport = Boolean.getBoolean("nclskins.wavey.noSupport");
            WaveyCapesSupport owned = null;
            if (!withoutSupport && installedSupport == null) {
                if (Boolean.getBoolean("nclskins.wavey.loaderBatch")) owned = WaveyCapesSupport.register();
                else WaveyCapesIntegration.install("1.11.3");
            }
            WaveyCapesSupport support = SupportManager.getSupportedMods().stream()
                    .filter(value -> value instanceof WaveyCapesSupport).map(value -> (WaveyCapesSupport) value).findFirst().orElse(null);
            try (ExactLocalPlayerScope scope = inspection ? new ExactLocalPlayerScope(actor, appearance) : new ExactLocalPlayerScope(actor)) {
                if (!withoutSupport) {
                    if (installedSupport != null) check(support == installedSupport, "INSTALLED_SUPPORT_IDENTITY");
                    check(support.shouldBeUsed(wrapper), "Selected installed material not admitted");
                    List<CapePass> passes = support.getCapePasses(wrapper);
                    check(passes.size() == (world ? 2 : 1) && !support.getCapeInfo(wrapper).isGlint(), "Native plan/legacy foil policy");
                    check(original(passes.get(0).renderType()) == CapeTranslucentFoilMaterial.forTexture(installed), "Base native material changed");
                    if (world) check(original(passes.get(1).renderType()) == (inspection ? RenderType.entityGlintDirect() : RenderType.entityGlint()), "Inspection direct/entity foil policy changed");
                    else check(original(passes.get(0).renderType()) instanceof CapeTranslucentFoilMaterial, "Worldless combined shader material missing");
                    check(passes.get(0).wrapper().apply(null) == null, "Host consumer wrapper is not identity");
                    if (installedSupport != null) ImmediateGuardProbe.policy(support, wrapper, passes);
                    try { passes.add(passes.get(0)); throw new AssertionError("Mutable pass plan"); }
                    catch (UnsupportedOperationException expected) {}
                }
                reset(); expectedActor = wrapper; expectedPose = new PoseStack();
                MultiBufferSource.BufferSource buffers = ImmediateBuffers.create();
                RenderType foreign = RenderType.entityCutoutNoCull(ResourceLocation.tryParse("nclskins:foreign"));
                if (scenario.equals("foreign-first")) { ImmediateBuffers.emit(buffers.getBuffer(foreign)); buffers.endLastBatch(); }
                if (scenario.equals("stale-before")) producer.coordinator.close();
                if (scenario.equals("stale-during")) afterGeometry = producer.coordinator::close;
                RuntimeException geometryAbort = new IllegalStateException("fixture geometry abort");
                if (scenario.equals("geometry-abort")) geometryFailure = geometryAbort;
                try {
                    new CapeNodeCollector().submitCape(buffers, wrapper, expectedPose, 157, 0.25f);
                    check(!scenario.equals("geometry-abort"), "Geometry exception swallowed");
                } catch (RuntimeException failure) {
                    check(scenario.equals("geometry-abort") && failure == geometryAbort, "Geometry exception identity changed");
                    geometryFailure = null;
                }
                if (scenario.equals("stale-after")) producer.coordinator.close();
                buffers.endLastBatch();
                if (scenario.equals("foreign-last") || scenario.startsWith("stale-")) {
                    ImmediateBuffers.emit(buffers.getBuffer(foreign)); buffers.endLastBatch();
                }
                if (withoutSupport && scenario.equals("live")) check(geometries > 0 && !acquired.isEmpty() && !drawn.isEmpty(), "NO_SUPPORT_NATIVE_PREREQUISITE");
                if (scenario.equals("geometry-abort")) check(geometries == 1, "ABORT_GEOMETRY_PREREQUISITE");
                long ownAcquisitions = acquired.stream().filter(type -> type instanceof CapturedCapePass).count();
                List<RenderType> ownDraws = drawn.stream().filter(type -> type != foreign).toList();
                if (scenario.equals("stale-before")) {
                    check(ownAcquisitions == 0 && !token.isValid(), "Invalid before admission acquired own material");
                } else if (scenario.equals("stale-during")) {
                    check(ownAcquisitions == (world ? 2 : 1) && ownDraws.isEmpty() && !token.isValid(), "Retired in loop reached native draw");
                } else if (scenario.equals("stale-after")) {
                    check(ownDraws.size() == (world ? 1 : 0) && !token.isValid(), "Retired pending material reached native draw");
                } else {
                    check(ownAcquisitions == (scenario.equals("geometry-abort") ? (withoutSupport ? 0 : 1) : world ? 2 : 1), "IMMEDIATE_OWN_FOIL_MISSING: genuine collector did not use own plan");
                    if (!scenario.equals("geometry-abort")) {
                        check(ownDraws.size() == (world ? 2 : 1), "IMMEDIATE_OWN_FOIL_MISSING: native consumer did not draw exact plan");
                        check(ownDraws.get(0) == CapeTranslucentFoilMaterial.forTexture(installed), "Native base order/identity changed");
                        if (world) check(ownDraws.get(1) == (inspection ? RenderType.entityGlintDirect() : RenderType.entityGlint()), "Duplicate or wrong native foil");
                        check(renderers.stream().allMatch(renderer -> renderer == support) && geometries == (world ? 2 : 1), "Genuine renderer selection/legacy early return changed");
                    }
                }
                if (scenario.startsWith("foreign-") || scenario.startsWith("stale-")) check(Collections.frequency(drawn, foreign) == 1, "Foreign native material lost/duplicated");
                check(ImmediateBuffers.pending() == 0, "Own CPU geometry terminal retained resource");
                int completedDraws = drawn.size(), completedReleases = releases;
                if (scenario.equals("live") && !withoutSupport) {
                    int profileReads = producer.profileReads.get(), imageReads = producer.imageReads.get();
                    support.getCapePasses(wrapper); support.shouldBeUsed(wrapper);
                    check(profileReads == producer.profileReads.get() && imageReads == producer.imageReads.get(), "Material render query performed I/O");
                    wrapper.invisible = true; check(!support.shouldBeUsed(wrapper), "Invisible actor admitted"); wrapper.invisible = false;
                    wrapper.elytra = true; check(!support.shouldBeUsed(wrapper), "Elytra admitted"); wrapper.elytra = false;
                    wrapper.visible = false; check(!support.shouldBeUsed(wrapper), "Hidden cape admitted"); wrapper.visible = true;
                    if (!inspection) {
                        for (PreviewRenderer.CapeMode mode : PreviewRenderer.CapeMode.values()) {
                            var suppressed = new PreviewRenderer.PreviewAppearance(appearance.skin(), appearance.model(), appearance.cape(),
                                    mode, appearance.outerLayerVisibility(), false, mode != PreviewRenderer.CapeMode.CAPE, false, token);
                            try (ExactLocalPlayerScope suppression = new ExactLocalPlayerScope(actor, suppressed)) {
                                check(!support.shouldBeUsed(wrapper) && support.getCapePasses(wrapper).isEmpty(), "Authoritative false/OFF/elytra borrowed world material");
                                reset(); expectedActor = wrapper; expectedPose = new PoseStack();
                                var plain = ImmediateBuffers.create();
                                new CapeNodeCollector().submitCape(plain, wrapper, expectedPose, 157, 0.25f);
                                plain.endLastBatch();
                                check(acquired.stream().noneMatch(type -> type instanceof CapturedCapePass)
                                        && drawn.size() == 1 && ImmediateBuffers.pending() == 0, "Suppressed preview used owned foil plan");
                            }
                            check(support.shouldBeUsed(wrapper), "Scope exit lost world material");
                        }
                    }
                }
                System.out.println("IMMEDIATE_COMPOSITION_PASS context=" + context + " scenario=" + scenario + " acquired=" + ownAcquisitions
                        + " nativeDraws=" + completedDraws + " releases=" + completedReleases + "; material-input boundary, not world publication; genuine collector/native consumer; GPU/visual NOT_RUN");
            } finally {
                if (owned != null) owned.close();
                if (!Boolean.getBoolean("nclskins.wavey.loaderBatch")) WaveyCapesIntegration.close();
                check(SupportManager.getSupportedMods().equals(before), "Foreign registrations changed");
            }
        } finally {
            check(Minecraft.getInstance() == previous && (previous == null || previous.getUser() == previousUser), "PREVIOUS_SUBJECT_RESTORED");
        }
    }
    private static RenderType original(RenderType type) { return (RenderType) field(type, CapturedCapePass.class, "original"); }
    public static final class Actor extends AbstractClientPlayer {
        private UUID identity;
        private Actor(ClientLevel level, com.mojang.authlib.GameProfile profile) { super(level, profile); }
        @Override public UUID getUUID() { return identity; }
        @Override public String getScoreboardName() { return "Self"; }
    }
    private static final class Wrapper extends PlayerWrapper {
        private final Player actor;
        private ResourceLocation texture;
        private net.minecraft.client.multiplayer.PlayerInfo info;
        private boolean invisible, elytra, visible = true;
        Wrapper(Actor actor) { super(actor); this.actor = actor; }
        @Override public Player getEntity() { return actor; }
        @Override public ResourceLocation getCapeTexture() { return info == null ? texture : ImmediateAppearanceInputs.cape(info); }
        @Override public boolean isPlayerInvisible() { return invisible; }
        @Override public boolean isCapeVisible() { return visible; }
        @Override public boolean hasElytraEquipped() { return elytra; }
    }
}
