package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.client.PreviewRenderer.*;
import com.naocraftlab.skins.compat.client.identifier.CapeMaterialSnapshot;
import com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass;
import com.naocraftlab.skins.compat.waveycapes.WaveyCapesSupport;
import com.naocraftlab.skins.runtime.*;
import dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.*;

public final class CapePublicationProbe {
    private static Avatar lastActor;
    private static AvatarRenderState lastState;
    private static int rawCalls;
    public static Avatar lastRawActor() { return lastActor; }
    public static AvatarRenderState lastRawState() { return lastState; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    public static void captureRaw(Avatar actor, AvatarRenderState state, float tick) {
        lastActor = actor;
        lastState = state;
        rawCalls++;
        state.skin = ((net.minecraft.client.entity.ClientAvatarEntity) actor).getSkin();
        if (Boolean.getBoolean("nclskins.wavey.wrongRaw")) state.skin = skin("nclskins:wrong_raw", true);
        state.showCape = true;
        state.isInvisible = false;
        state.chestEquipment = net.minecraft.world.item.ItemStack.EMPTY;
    }

    public static void main(String[] args) throws Exception {
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        runCase(args);
    }
    public static void reset() { lastActor = null; lastState = null; rawCalls = 0; }
    public static void runCase(String[] args) throws Exception {
        try (HeadlessCapeClient client = HeadlessCapeClient.open(true);
                WaveyProducerFixture producer = new WaveyProducerFixture(Path.of(args[0]));
                CapeRegistry registry = new CapeRegistry();
                PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry, TextureRegistry.TextureKind.IMAGE,
                        com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
                WaveyCapesSupport support = WaveyCapesSupport.register()) {
            var user = Minecraft.class.getDeclaredField("user");
            user.setAccessible(true);
            user.set(Minecraft.getInstance(), new net.minecraft.client.User("Self", producer.self, "", Optional.empty(), Optional.empty()));
            dev.tr7zw.waveycapes.versionless.ModBase.config = new dev.tr7zw.waveycapes.versionless.config.Config();
            dev.tr7zw.waveycapes.versionless.ModBase.config.capeStyle = dev.tr7zw.waveycapes.versionless.CapeStyle.valueOf(args.length > 1 ? args[1] : "SMOOTH");
            AvatarRenderer renderer = allocate(AvatarRenderer.class);
            String installed = CapeProjection.material(producer.self, "Self", true).capeLocation();
            RawActor self = actor(producer.self, "Self", installed);
            var token = CapeProjection.captureCapeTexture(producer.self, "Self", true, installed);
            AvatarRenderState state = publish(renderer, self);
            check(material(state) != null && material(state).validity() == token && support.shouldBeUsed(wrapper(state)),
                    "WORLD_PUBLICATION_MISSING: actual production hook did not publish self material");
            collect(state, support);
            UUID remoteId = UUID.randomUUID();
            producer.coordinator.trackedPlayer(remoteId, "Remote");
            producer.drain();
            String remoteInstalled = CapeProjection.material(remoteId, "Remote", false).capeLocation();
            check(installed.equals(remoteInstalled), "Same-pixels actors did not share raw location");
            RawActor remote = actor(remoteId, "Remote", remoteInstalled);
            AvatarRenderState remoteState = publish(renderer, remote);
            check(material(remoteState) != null && support.shouldBeUsed(wrapper(remoteState)), "Remote publication absent");
            collect(remoteState, support);
            check(material(publish(renderer, actor(UUID.randomUUID(), "Untracked", installed))) == null,
                    "Unknown same-pixels actor borrowed self ownership");
            RawActor mismatch = actor(producer.self, "Self", "nclskins:other");
            check(material(publish(renderer, mismatch)) == null, "Mismatched raw texture admitted");
            RawActor nameless = actor(producer.self, null, installed);
            check(material(publish(renderer, nameless)) == null, "Nameless raw actor admitted");
            cache.request("inspected-loser", () -> CompletableFuture.completedFuture(Optional.of(producer.firstFramePng)), () -> {});
            var inspected = cache.handle("inspected-loser").orElseThrow();
            var previewToken = cache.capture("inspected-loser");
            check(previewToken != token && previewToken.isValid(), "Inspection must own a separate cache incarnation");
            self.rawSkin = skin(inspected.location(), true);
            PreviewAppearance appearance = appearance(inspected, CapeMode.CAPE, true, previewToken);
            try (ExactLocalPlayerScope scope = new ExactLocalPlayerScope(self, appearance)) {
                AvatarRenderState inspectedState = publish(renderer, self);
                check(material(inspectedState) != null && material(inspectedState).validity() == previewToken
                        && support.shouldBeUsed(wrapper(inspectedState)), "Inspected losing provider borrowed world token");
                collect(inspectedState, support);
                check(material(publish(renderer, remote)).validity() != previewToken, "Adjacent actor borrowed inspection");
                cache.close();
                check(!support.shouldBeUsed(wrapper(inspectedState)) && token.isValid(), "Inspection retirement failed isolation");
            }
            check(ExactLocalPlayerScope.capeGlint(self, inspected.location()) == null
                    && material(publish(renderer, self)) == null, "Scope exit retained losing source");
            self.rawSkin = skin(installed, true);
            AvatarRenderState reused = publish(renderer, self);
            for (CapeMode mode : CapeMode.values()) {
                try (ExactLocalPlayerScope scope = new ExactLocalPlayerScope(self,
                        appearance(new TextureRegistry.TextureHandle(installed, 128, 64), mode, mode != CapeMode.CAPE, token))) {
                    renderer.extractRenderState(self, reused, 0.25F);
                    check(lastState == reused && material(reused) == null,
                            "Authoritative false/OFF/elytra retained previous publication");
                }
            }
            check(support.shouldBeUsed(wrapper(publish(renderer, self))), "Scope exit did not restore world");
            int loads = producer.sink.registry.loads;
            producer.glintOnlyRefresh(false);
            check(material(publish(renderer, self)) == null, "Reader refresh false did not reach publication");
            producer.glintOnlyRefresh(true);
            check(material(publish(renderer, self)).validity() == token && producer.sink.registry.loads == loads,
                    "Reader glint-only update replaced incarnation");
            int profileReads = producer.profileReads.get(), imageReads = producer.imageReads.get();
            for (int i = 0; i < 10; i++) support.getCapePasses(wrapper(publish(renderer, self)));
            check(producer.profileReads.get() == profileReads && producer.imageReads.get() == imageReads, "Support performed I/O");
            producer.coordinator.close();
            check(!support.shouldBeUsed(wrapper(state)) && !support.shouldBeUsed(wrapper(remoteState)), "Retired publication remained admitted");
            System.out.println("WORLD_PUBLICATION_PASS actual reader/coordinator -> raw native extraction -> NORMAL production RETURN hook/carrier -> genuine support/collector; self/remote/same-pixels/loser/false/OFF/elytra/scope/retirement; rawCalls=" + rawCalls);
        }
    }

    public static PreviewAppearance appearance(TextureRegistry.TextureHandle cape, CapeMode mode, boolean glint, TextureUseValidity token) {
        return new PreviewAppearance(new TextureRegistry.TextureHandle("nclskins:preview_skin", 64, 64), SkinModel.SLIM,
                mode == CapeMode.OFF ? Optional.empty() : Optional.of(cape), mode, OuterLayerVisibility.allVisible(), true, glint, false, token);
    }
    public static AvatarRenderState publish(AvatarRenderer renderer, RawActor actor) {
        AvatarRenderState state = new AvatarRenderState();
        int before = rawCalls;
        renderer.extractRenderState(actor, state, 0.25F);
        check(rawCalls == before + 1 && lastActor == actor && lastState == state, "Raw continuation/state identity changed");
        return state;
    }
    public static CapeMaterialSnapshot material(AvatarRenderState state) { return ((CapeMaterialSnapshot.Carrier) state).nclskins$capeMaterial(); }
    public static PlayerWrapper wrapper(AvatarRenderState state) {
        return new PlayerWrapper(state) {
            @Override public Identifier getCapeTexture() { return state.skin.cape() == null ? null : state.skin.cape().texturePath(); }
        };
    }
    public static void collect(AvatarRenderState state, WaveyCapesSupport support) {
        PlayerWrapper actor = wrapper(state);
        var passes = support.getCapePasses(actor);
        check(!passes.isEmpty() && !support.getCapeInfo(actor).isGlint(), "Missing public plan/legacy foil policy");
        check(CapturedCapePass.of(passes.getFirst().renderType()).validity() == material(state).validity(), "Published token lost");
        PublicationCapeCollector.collect(state, support);
    }

    public static PlayerSkin skin(String location, boolean cape) {
        Identifier id = Identifier.parse(location);
        var texture = new ClientAsset.ResourceTexture(id, id);
        return PlayerSkin.insecure(texture, cape ? texture : null, null, PlayerModelType.SLIM);
    }
    public static RawActor actor(UUID id, String name, String location) throws Exception {
        RawActor actor = allocate(RawActor.class); actor.id = id; actor.name = name; actor.rawSkin = skin(location, true); return actor;
    }
    public static <T> T allocate(Class<T> type) throws Exception {
        var singleton = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        return type.cast(unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type));
    }
    public static final class RawActor extends RemotePlayer {
        private UUID id;
        private String name;
        private PlayerSkin rawSkin;
        private RawActor() { super(null, null); }
        @Override public UUID getUUID() { return id; }
        @Override public String getScoreboardName() { return name; }
        @Override public PlayerSkin getSkin() { return rawSkin; }
    }
}
