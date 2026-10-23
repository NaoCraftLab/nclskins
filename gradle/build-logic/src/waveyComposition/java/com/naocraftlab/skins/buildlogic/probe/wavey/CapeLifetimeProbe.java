package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.blaze3d.vertex.*;
import com.naocraftlab.skins.compat.client.identifier.foil.*;
import com.naocraftlab.skins.runtime.*;
import com.naocraftlab.skins.client.TextureUseValidity;
import dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper;
import dev.tr7zw.waveycapes.render.*;
import dev.tr7zw.waveycapes.support.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.feature.*;
import net.minecraft.client.renderer.feature.phase.*;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.resources.Identifier;

public final class CapeLifetimeProbe extends CustomFeatureRenderer {
    private static Runnable retireAtForeign;
    public static void reset() { retireAtForeign = null; }
    public static boolean hasPendingRetirement() { return retireAtForeign != null; }
    private static void foreign(VertexConsumer consumer) {
        if (retireAtForeign != null) {
            Runnable action = retireAtForeign;
            retireAtForeign = null;
            action.run();
        }
        consumer.addVertex(0, 0, 0);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        runCase(args);
    }
    public static void runCase(String[] args) throws Exception {
        String scenario = args[1];
        boolean foreignFirst = args[2].equals("foreign-first");
        try (HeadlessCapeClient client = HeadlessCapeClient.open(false); CapeRegistry independentRegistry = new CapeRegistry(); WaveyProducerFixture producer = new WaveyProducerFixture(Path.of(args[0]))) {
            var selected = CapeProjection.material(producer.self, "Self", true);
            check(selected.provider() == com.naocraftlab.skins.core.provider.BuiltinProvider.MINECRAFTCAPES
                    && selected.capeGlint() && selected.capeLocation() != null, "Producer material absent");
            String installed = selected.capeLocation();
            TextureUseValidity token = CapeProjection.captureCapeTexture(producer.self, "Self", true, installed);
            check(token.isValid(), "Exact current capture port absent");
            check(!CapeProjection.captureCapeTexture(producer.self, "Other", true, installed).isValid(), "Name isolation");
            check(!CapeProjection.captureCapeTexture(UUID.randomUUID(), "Self", false, installed).isValid(), "Actor isolation");
            check(!CapeProjection.captureCapeTexture(producer.self, "Self", true, "nclskins:other").isValid(), "Frame isolation");
            RenderType nativeBase = RenderTypes.entityTranslucent(Identifier.parse(installed));
            RenderType base = CapturedCapePass.material(nativeBase, token, false);
            RenderType foil = OrderedCapeFoil.pass(token);
            var independentHandle = independentRegistry.registerRgba(com.naocraftlab.skins.client.TextureRegistry.TextureKind.IMAGE,
                    "b".repeat(64), 1, 1, new byte[4], () -> {});
            var independentToken = independentRegistry.capture(independentHandle);
            RenderType independent = CapturedCapePass.material(
                    RenderTypes.entityTranslucent(Identifier.parse(independentHandle.location())), independentToken, false);
            check(((CapturedCapePass.Carrier) base).nclskins$setup()
                    == ((CapturedCapePass.Carrier) nativeBase).nclskins$setup(), "Base setup changed");
            check(((CapturedCapePass.Carrier) foil).nclskins$setup()
                    == ((CapturedCapePass.Carrier) RenderTypes.entityGlint()).nclskins$setup(), "Foil setup changed");
            CapeRenderer renderer = new CapeRenderer() {
                public CapeInfos getCapeInfo(PlayerWrapper actor) { throw new AssertionError("Legacy glint path reached"); }
                public List<CapePass> getCapePasses(PlayerWrapper actor) {
                    return List.of(new CapePass(base, consumer -> {
                        CapeHost.wrappers++;
                        check(token.isValid(), "Stale wrapper reached");
                        return consumer;
                    }), new CapePass(foil, consumer -> { CapeHost.wrappers++; return consumer; }));
                }
            };
            ModSupport support = new ModSupport() {
                public boolean shouldBeUsed(PlayerWrapper actor) { return true; }
                public CapeRenderer getRenderer() { return renderer; }
                public boolean blockFeatureRenderer(Object actor) { return false; }
                public int priority() { return 1; }
            };
            check(SupportManager.getSupportedMods().add(support), "Registration failed");
            try {
                SubmitNodeStorage storage = new SubmitNodeStorage();
                storage.submitCustomGeometry(new PoseStack(), nativeBase, (pose, consumer) -> foreign(consumer));
                new CapeNodeCollector().submitCape(storage, null, new PoseStack(), 0, 0);
                storage.submitCustomGeometry(new PoseStack(), nativeBase, (pose, consumer) -> foreign(consumer));
                storage.submitCustomGeometry(new PoseStack(), independent, (pose, consumer) -> consumer.addVertex(0, 0, 0));
                check(storage.getSubmitsPerOrder().size() == 2 && CapeHost.geometry == 0, "Production order route failed");
                Runnable retire = () -> {
                    if (scenario.startsWith("replace")) producer.retireFrame();
                    else if (scenario.startsWith("registry-close")) producer.sink.registry.close();
                    else producer.coordinator.close();
                    check(!token.isValid() && !producer.sink.registry.live(installed), "Owner retirement failed");
                };
                boolean before = scenario.endsWith("before"), after = scenario.endsWith("after");
                boolean during = scenario.equals("retire-during");
                if (before) retire.run();
                if (during) retireAtForeign = retire;
                List<CustomFeatureRenderer.Submit> pending = new ArrayList<>();
                storage.drainPhases(phase -> phase.sortInto(new FeatureRenderPhase.Output() {
                    public void accept(SubmitNode node, boolean ordered) { throw new AssertionError("Singleton"); }
                    public <T extends SubmitNode> void acceptFeatureGroup(FeatureRendererType<T> type,
                            Collection<T> nodes, boolean ordered) {
                        for (T node : nodes) pending.add((CustomFeatureRenderer.Submit) node);
                    }
                }));
                check(pending.size() == 5, "Genuine public pass dispatch lost");
                var context = new FeatureFrameContext(null, null, null, null, null, null, null, CapeStaged.headless());
                java.util.concurrent.atomic.AtomicReference<Throwable> offThreadFailure = new java.util.concurrent.atomic.AtomicReference<>();
                var ownSubmit = pending.stream().filter(submit -> CapturedCapePass.of(submit.renderType()) != null
                        && CapturedCapePass.of(submit.renderType()).validity() == token).findFirst().orElseThrow();
                Thread nonClient = new Thread(() -> {
                    var rejected = new CapeLifetimeProbe();
                    try {
                        rejected.prepareGroup(context, List.of(ownSubmit), false);
                        rejected.executeGroup(context, 0, List.of(ownSubmit), false);
                    } catch (Throwable failure) { offThreadFailure.set(failure); }
                    finally { rejected.finishExecute(context); }
                }, "cape-capture-non-client-control");
                nonClient.start(); nonClient.join();
                check(offThreadFailure.get() == null && CapeHost.prepares == 0 && CapeHost.geometry == 0
                        && CapeHost.wrappers == 0 && CapeHost.draws == 0, "Non-client own continuation was not fail-closed");
                pending.sort(Comparator.comparingInt(submit -> {
                    boolean own = CapturedCapePass.of(submit.renderType()) != null;
                    return foreignFirst ? own ? 1 : 0 : own ? 0 : 1;
                }));
                List<CustomFeatureRenderer.Submit> bases = new ArrayList<>(), foils = new ArrayList<>();
                for (var submit : pending) {
                    CapturedCapePass capture = CapturedCapePass.of(submit.renderType());
                    (capture != null && capture.orderedFoil() ? foils : bases).add(submit);
                }
                CapeLifetimeProbe consumer = new CapeLifetimeProbe();
                try {
                    consumer.prepareGroup(context, bases, false);
                    consumer.prepareGroup(context, foils, false);
                    if (after) retire.run();
                    consumer.executeGroup(context, 0, bases, false);
                    consumer.executeGroup(context, 1, foils, false);
                } finally {
                    consumer.finishExecute(context);
                }
                check(independentToken.isValid(), "Unrelated physical incarnation was retired");
                check(CapeHost.foreignVertices == 2, "Foreign equal-material geometry lost");
                check(CapeHost.ownVertices == (before || after || during ? 1 : 3), "Independent/own geometry lost");
                int expectedGeometry = before ? 0 : during ? foreignFirst ? 0 : 1 : 2;
                check(CapeHost.geometry == expectedGeometry, "Unexpected genuine callbacks");
                check(CapeHost.wrappers == expectedGeometry, "Unexpected genuine wrappers");
                check(producer.profileReads.get() == 1 && producer.imageReads.get() == 1, "Render caused provider I/O");
                if (!before && !during) {
                    check(CapeHost.prepared.stream().anyMatch(a -> CapeHost.prepared.stream().anyMatch(b ->
                            a != b && a.equals(b)
                            && (((CapturedCapePass.PreparedCarrier) (Object) a).nclskins$capture() == null)
                            != (((CapturedCapePass.PreparedCarrier) (Object) b).nclskins$capture() == null))),
                            "Prepared equality collision not reached");
                }
                System.out.println("PRODUCTION_WOVEN_CAPTURE_PASS scenario=" + scenario + " order=" + args[2]
                        + " prepares=" + CapeHost.prepares + " wrappers=" + CapeHost.wrappers + " geometry=" + CapeHost.geometry
                        + " draws=" + CapeHost.draws + " foreignVertices=" + CapeHost.foreignVertices
                        + " ownVertices=" + CapeHost.ownVertices + "; real reader HD128x64 PNG3frames; no GPU");
            } finally {
                SupportManager.getSupportedMods().remove(support);
            }
        }
    }
}
