package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.compat.client.identifier.CapeMaterialSnapshot;
import com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass;
import com.naocraftlab.skins.compat.waveycapes.WaveyCapesSupport;
import com.naocraftlab.skins.runtime.*;
import dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper;
import dev.tr7zw.waveycapes.render.CapePass;
import dev.tr7zw.waveycapes.support.*;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;

public final class CapeBridgeProbe extends net.minecraft.client.renderer.feature.CustomFeatureRenderer {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        boolean world = args.length > 1 && args[1].equals("world");
        try (HeadlessCapeClient client = HeadlessCapeClient.open(world); WaveyProducerFixture producer = new WaveyProducerFixture(Path.of(args[0]))) {
            String style = args.length > 2 ? args[2] : "SMOOTH";
            dev.tr7zw.waveycapes.versionless.ModBase.config = new dev.tr7zw.waveycapes.versionless.config.Config();
            dev.tr7zw.waveycapes.versionless.ModBase.config.capeStyle = dev.tr7zw.waveycapes.versionless.CapeStyle.valueOf(style);
            String installed = CapeProjection.material(producer.self, "Self", true).capeLocation();
            var token = CapeProjection.captureCapeTexture(producer.self, "Self", true, installed);
            State state = allocate(State.class);
            Wrapper actor = new Wrapper(state);
            actor.installed = Identifier.parse(installed);
            state.material = CapeMaterialSnapshot.world(producer.self, "Self", true, installed);
            ModSupport foreign = new ModSupport() {
                public boolean shouldBeUsed(PlayerWrapper player) { return false; }
                public dev.tr7zw.waveycapes.render.CapeRenderer getRenderer() { throw new AssertionError("Rejected foreign renderer"); }
                public int priority() { return 1; }
                public boolean blockFeatureRenderer(Object player) { return false; }
            };
            check(SupportManager.getSupportedMods().add(foreign), "Priority collision fixture");
            Set<ModSupport> before = new HashSet<>(SupportManager.getSupportedMods());
            boolean withoutRegistration = Boolean.getBoolean("nclskins.wavey.noSupport");
            if (!withoutRegistration) {
                com.naocraftlab.skins.compat.waveycapes.WaveyCapesIntegration.install("1.11.3");
                com.naocraftlab.skins.compat.waveycapes.WaveyCapesIntegration.install("1.11.3");
            }
            var owned = SupportManager.getSupportedMods().stream().filter(value -> value instanceof WaveyCapesSupport).toList();
            check(withoutRegistration || owned.size() == 1, "Repeated install added another support");
            WaveyCapesSupport support = withoutRegistration ? new WaveyCapesSupport() : (WaveyCapesSupport) owned.getFirst();
            check(support != null && (withoutRegistration || SupportManager.getSupportedMods().contains(support)), "Registration absent");
            try {
                check(support.priority() == 1 && !support.blockFeatureRenderer(actor), "Support precedence/feature policy");
                check(support.shouldBeUsed(actor), "Producer-to-production-support admission failed");
                List<CapePass> passes = support.getCapePasses(actor);
                check(passes.size() == (world ? 2 : 1), "Native world/combined policy pass count");
                check(!support.getCapeInfo(actor).isGlint(), "Duplicate legacy foil");
                check(CapturedCapePass.of(passes.getFirst().renderType()).validity() == token, "Capture lost in public pass");
                CapeGuardProbe.assertWrappers(passes);
                var storage = new net.minecraft.client.renderer.SubmitNodeStorage();
                new dev.tr7zw.waveycapes.render.CapeNodeCollector().submitCape(storage, actor,
                        new com.mojang.blaze3d.vertex.PoseStack(), 0, 0);
                java.util.List<net.minecraft.client.renderer.feature.CustomFeatureRenderer.Submit> pending = new java.util.ArrayList<>();
                storage.drainPhases(phase -> phase.sortInto(new net.minecraft.client.renderer.feature.phase.FeatureRenderPhase.Output() {
                    public void accept(net.minecraft.client.renderer.feature.submit.SubmitNode node, boolean ordered) {
                        throw new AssertionError("Unexpected singleton");
                    }
                    public <T extends net.minecraft.client.renderer.feature.submit.SubmitNode> void acceptFeatureGroup(
                            net.minecraft.client.renderer.feature.FeatureRendererType<T> type, java.util.Collection<T> nodes, boolean ordered) {
                        for (T node : nodes) pending.add((net.minecraft.client.renderer.feature.CustomFeatureRenderer.Submit) node);
                    }
                }));
                var context = new net.minecraft.client.renderer.feature.FeatureFrameContext(
                        null, null, null, null, null, null, null, CapeStaged.headless());
                CapeBridgeProbe consumer = new CapeBridgeProbe();
                java.util.List<net.minecraft.client.renderer.feature.CustomFeatureRenderer.Submit> bases = new java.util.ArrayList<>();
                java.util.List<net.minecraft.client.renderer.feature.CustomFeatureRenderer.Submit> foils = new java.util.ArrayList<>();
                for (var submit : pending) {
                    CapturedCapePass capture = CapturedCapePass.of(submit.renderType());
                    (capture != null && capture.orderedFoil() ? foils : bases).add(submit);
                }
                try {
                    consumer.prepareGroup(context, bases, false);
                    if (!foils.isEmpty()) consumer.prepareGroup(context, foils, false);
                    consumer.executeGroup(context, 0, bases, false);
                    if (!foils.isEmpty()) consumer.executeGroup(context, 1, foils, false);
                } finally { consumer.finishExecute(context); }
                check(pending.size() == passes.size() && CapeHost.draws == passes.size()
                        && CapeHost.geometry == passes.size() && CapeHost.ownVertices == passes.size(),
                        "NATIVE_BRIDGE_FOIL_MISSING: genuine collector/native consumer did not consume the owned material plan");
                check(CapeHost.renderers.size() == 1 && CapeHost.renderers.contains(support)
                        && support.vanillaUvValues() && support.uvSheetWidth() == 64 && support.uvSheetHeight() == 32,
                        "Native geometry delegation/logical UV contract changed");
                try { passes.add(passes.getFirst()); throw new AssertionError("Mutable public pass plan"); }
                catch (UnsupportedOperationException expected) {}
                int registrations = producer.sink.registry.loads;
                producer.glintOnlyRefresh(false);
                state.material = CapeMaterialSnapshot.world(producer.self, "Self", true, installed);
                check(state.material == null && !support.shouldBeUsed(actor), "Refresh false did not reach support");
                producer.glintOnlyRefresh(true);
                state.material = CapeMaterialSnapshot.world(producer.self, "Self", true, installed);
                check(state.material != null && state.material.validity() == token && token.isValid()
                        && producer.sink.registry.loads == registrations && support.shouldBeUsed(actor),
                        "Glint-only refresh replaced the current native incarnation");
                int profileReads = producer.profileReads.get(), imageReads = producer.imageReads.get();
                state.material = new CapeMaterialSnapshot(installed, false, token);
                check(!support.shouldBeUsed(actor) && support.getCapePasses(actor).isEmpty(), "Explicit false borrowed world glint");
                state.material = null;
                check(!support.shouldBeUsed(actor), "No-cape/suppressed state borrowed world glint");
                state.material = new CapeMaterialSnapshot(installed, true, TextureUseValidity.INVALID);
                check(!support.shouldBeUsed(actor), "Uncapturable preview borrowed world token");
                state.material = CapeMaterialSnapshot.world(producer.self, "Self", true, installed);
                actor.elytra = true;
                check(!support.shouldBeUsed(actor), "Elytra inherited provider glint");
                actor.elytra = false; actor.invisible = true;
                check(!support.shouldBeUsed(actor), "Invisible actor admitted");
                actor.invisible = false; actor.visible = false;
                check(!support.shouldBeUsed(actor), "Hidden cape admitted");
                actor.visible = true;
                actor.installed = Identifier.parse("nclskins:other");
                check(!support.shouldBeUsed(actor), "Texture mismatch admitted");
                actor.installed = Identifier.parse(installed);
                producer.coordinator.close();
                check(!support.shouldBeUsed(actor) && support.getCapePasses(actor).isEmpty(), "Retired producer admitted");
                check(producer.profileReads.get() == profileReads && producer.imageReads.get() == imageReads, "Bridge caused I/O");
            } finally {
                com.naocraftlab.skins.compat.waveycapes.WaveyCapesIntegration.close();
                support.close();
                support.close();
            }
            check(!support.shouldBeUsed(actor), "Closed support activated");
            com.naocraftlab.skins.compat.waveycapes.WaveyCapesIntegration.install("1.11.3");
            check(SupportManager.getSupportedMods().equals(before), "Foreign registrations changed or late activation recreated own support");
            SupportManager.getSupportedMods().remove(foreign);
            System.out.println("PRODUCTION_BRIDGE_PASS world=" + world + " style=" + style + ": real producer/current material helper -> production support/plan -> genuine collector -> production-woven consumer; explicit false/no-cape/invalid/elytra/visibility/mismatch/retired/closed; immutable passes, no legacy foil; synthetic wrapper boundary, no GPU");
        }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }

    public static final class State extends AvatarRenderState implements CapeMaterialSnapshot.Carrier {
        private CapeMaterialSnapshot material;
        @Override public CapeMaterialSnapshot nclskins$capeMaterial() { return material; }
        @Override public void nclskins$setCapeMaterial(CapeMaterialSnapshot value) { material = value; }
    }
    private static final class Wrapper extends PlayerWrapper {
        private Identifier installed;
        private boolean visible = true, invisible, elytra;
        Wrapper(State state) { super(state); }
        @Override public Identifier getCapeTexture() { return installed; }
        @Override public boolean isPlayerInvisible() { return invisible; }
        @Override public boolean isCapeVisible() { return visible; }
        @Override public boolean hasElytraEquipped() { return elytra; }
    }
}
