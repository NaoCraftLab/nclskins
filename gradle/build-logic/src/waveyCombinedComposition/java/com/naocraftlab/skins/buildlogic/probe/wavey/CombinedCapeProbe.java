package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.compat.client.identifier.CapeMaterialSnapshot;
import com.naocraftlab.skins.compat.client.identifier.foil.*;
import com.naocraftlab.skins.compat.waveycapes.WaveyCapesSupport;
import com.naocraftlab.skins.runtime.*;
import dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper;
import dev.tr7zw.waveycapes.render.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.feature.*;
import net.minecraft.client.renderer.feature.phase.*;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.resources.Identifier;

public final class CombinedCapeProbe {
    private static final RuntimeException FAILURE = new IllegalStateException("combined-fixture-abort");
    private static void check(boolean value, String message) { CombinedCapeHost.check(value, message); }

    public static void main(String[] args) throws Exception {
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        runCase(args);
    }
    public static void runCase(String[] args) throws Exception {
        boolean world = args[1].equals("world");
        String scenario = args[2];
        boolean foreignFirst = args[3].equals("foreign-first");
        OitStage stage = args[4].equals("normal") ? null : OitStage.valueOf(args[4]);
        try (HeadlessCapeClient client = HeadlessCapeClient.open(true);
                WaveyProducerFixture producer = new WaveyProducerFixture(Path.of(args[0]));
                CapeRegistry independentRegistry = new CapeRegistry();
                WaveyCapesSupport support = WaveyCapesSupport.register()) {
            if (!world) Minecraft.getInstance().level = null;
            initializeRenderOptions();
            dev.tr7zw.waveycapes.versionless.ModBase.config = new dev.tr7zw.waveycapes.versionless.config.Config();
            dev.tr7zw.waveycapes.versionless.ModBase.config.capeStyle = dev.tr7zw.waveycapes.versionless.CapeStyle.valueOf(args[5]);
            String location = CapeProjection.material(producer.self, "Self", true).capeLocation();
            var token = CapeProjection.captureCapeTexture(producer.self, "Self", true, location);
            check(token.isValid(), "Reader/coordinator current capture absent");
            AvatarRenderState state = new AvatarRenderState();
            state.showCape = true;
            var carrier = (CapeMaterialSnapshot.Carrier) state;
            carrier.nclskins$setCapeMaterial(CapeMaterialSnapshot.world(producer.self, "Self", true, location));
            PlayerWrapper actor = new PlayerWrapper(state) { @Override public Identifier getCapeTexture() { return Identifier.parse(location); } };
            check(support.shouldBeUsed(actor), "COMBINED_SUPPORT_MISSING");
            List<CapePass> publicPlan = support.getCapePasses(actor);
            check(publicPlan.size() == 1 && !support.getCapeInfo(actor).isGlint(), "COMBINED_PLAN_NOT_SINGLE");
            try { publicPlan.add(publicPlan.getFirst()); throw new AssertionError("Mutable public plan"); }
            catch (UnsupportedOperationException expected) {}
            RenderType nativeMaterial = CapeTranslucentFoilMaterial.forTexture(Identifier.parse(location));
            RenderSetup setup = ((CapturedCapePass.Carrier) nativeMaterial).nclskins$setup();
            if (world) check(nativeMaterial == RenderTypes.entitySolidGlint(Identifier.parse(location)), "World native policy changed");
            else check(nativeMaterial.pipeline().getLocation().toString().equals("minecraft:pipeline/nclskins_cape_translucent_foil"), "Worldless combined pipeline changed");
            check(((CapturedCapePass.Carrier) publicPlan.getFirst().renderType()).nclskins$setup() == setup, "Public plan reconstructed setup");
            check(!CapturedCapePass.of(publicPlan.getFirst().renderType()).orderedFoil(), "Combined used split order marker");
            if (scenario.equals("no-support")) support.close();
            if (scenario.equals("wrong-material")) carrier.nclskins$setCapeMaterial(new CapeMaterialSnapshot("nclskins:wrong_material", true, token));
            SubmitNodeStorage storage = new SubmitNodeStorage();
            storage.setUseImprovedTransparency(stage != null);
            PoseStack pose = new PoseStack();
            new CapeNodeCollector().submitCape(storage, actor, pose, 31, 0.25F);
            List<CustomFeatureRenderer.Submit> own = drain(storage);
            check(own.size() == 1 && CapturedCapePass.of(own.getFirst().renderType()) != null
                    && CapturedCapePass.of(own.getFirst().renderType()).validity() == token, "COMBINED_INTEGRATION_MISSING");
            check(((CapturedCapePass.Carrier) own.getFirst().renderType()).nclskins$setup() == setup, "Collector material identity changed");
            var collected = own.getFirst();
            check(collected.pose() != null && collected.pose().pose().equals(pose.last().pose()), "Collected pose changed");
            var callback = collected.customGeometryRenderer();
            RenderType foreignType = RenderType.create("nclskins_captured_cape", setup);
            var independentHandle = independentRegistry.registerRgba(TextureRegistry.TextureKind.IMAGE, "c".repeat(64), 1, 1, new byte[4], () -> {});
            var independentToken = independentRegistry.capture(independentHandle);
            RenderType independentType = CapturedCapePass.material(nativeMaterial, independentToken, false);
            Runnable retire = () -> { producer.coordinator.close(); check(!token.isValid() && independentToken.isValid(), "Retirement crossed owner boundary"); };
            var foreignCalls = new int[1];
            SubmitNodeCollector.CustomGeometryRenderer foreignCallback = (p, consumer) -> {
                check(p == pose.last(), "Foreign pose identity changed");
                check(consumer == CombinedCapeHost.currentConsumer, "Foreign consumer identity changed");
                foreignCalls[0]++;
                if (scenario.equals("retire-build")) retire.run();
                if (scenario.equals("foreign-exception")) throw FAILURE;
                consumer.addVertex(0, 0, 0);
            };
            CustomFeatureRenderer.Submit foreign = new CustomFeatureRenderer.Submit(pose.last(), foreignType, foreignCallback);
            CustomFeatureRenderer.Submit independent = new CustomFeatureRenderer.Submit(pose.last(), independentType, (p, consumer) -> consumer.addVertex(0, 0, 0));
            List<CustomFeatureRenderer.Submit> pending = new ArrayList<>();
            if (foreignFirst) pending.add(foreign);
            pending.add(collected);
            if (!foreignFirst) pending.add(foreign);
            pending.add(independent);
            check(pending.stream().anyMatch(s -> s.customGeometryRenderer() == callback && s.renderType() == collected.renderType()), "Storage continuation identity changed");
            CombinedCapeHost.reset();
            CombinedCapeHost.expectedActor = actor; CombinedCapeHost.expectedRenderer = support;
            CombinedCapeHost.expectedStage = stage;
            RenderPass pass = (RenderPass) Proxy.newProxyInstance(RenderPass.class.getClassLoader(), new Class<?>[]{RenderPass.class}, (p, m, a) -> { throw new AssertionError("GPU API reached: " + m.getName()); });
            CombinedCapeHost.expectedPass = pass;
            if (scenario.equals("stale-before")) retire.run();
            if (scenario.equals("own-exception")) CombinedCapeHost.geometryFailure = FAILURE;
            if (scenario.equals("draw-exception")) CombinedCapeHost.drawFailure = FAILURE;
            CombinedCapeStaged staged = CombinedCapeStaged.open();
            FeatureFrameContext context = new FeatureFrameContext(null, null, null, null, null, null, null, staged);
            CustomFeatureRenderer consumer = new CustomFeatureRenderer();
            boolean abort = scenario.endsWith("exception");
            Throwable actual = null;
            try {
                consumer.prepareGroup(context, pending, false);
                if (scenario.equals("stale-after")) retire.run();
                if (scenario.equals("retire-draw")) CombinedCapeHost.retireAtDraw = retire;
                consumer.executeGroup(context, stage, pass, 0, pending, false);
            } catch (Throwable failure) { actual = failure; }
            finally { consumer.finishExecute(context); }
            if (abort) check(actual == FAILURE, "Exception identity was swallowed/replaced: " + actual);
            else {
                if (actual != null) throw new AssertionError("Native composition failed", actual);
                boolean skippedBefore = scenario.equals("stale-before") || scenario.equals("retire-build") && foreignFirst;
                int expectedGeometry = skippedBefore ? 0 : 1;
                int expectedOwnDraw = scenario.equals("live") || scenario.equals("retire-draw") && !foreignFirst ? 1 : 0;
                check(CombinedCapeHost.geometry == expectedGeometry, "Combined geometry not exactly once");
                check(foreignCalls[0] == 1, "Foreign callback count changed");
                check(CombinedCapeHost.drawn.size() == 2 + expectedOwnDraw, "Draw count/independent geometry changed");
                check(CombinedCapeHost.drawn.stream().filter(p -> { var c = ((CapturedCapePass.PreparedCarrier) (Object) p).nclskins$capture(); return c != null && c.validity() == token; }).count() == expectedOwnDraw, "Own stale/live draw selection changed");
                check(CombinedCapeHost.drawn.stream().anyMatch(p -> { var c = ((CapturedCapePass.PreparedCarrier) (Object) p).nclskins$capture(); return c != null && c.validity() == independentToken; }), "Independent capture lost");
                check(CombinedCapeHost.acquired.size() == (skippedBefore ? 2 : 3), "Invalid capture acquired native resources");
                if (!skippedBefore) check(CombinedCapeHost.prepared.stream().anyMatch(a -> CombinedCapeHost.prepared.stream().anyMatch(b -> a != b && a.equals(b))), "Native prepared equality collision not exercised");
                if (scenario.equals("live")) {
                    int before = CombinedCapeHost.drawn.size();
                    consumer.prepareGroup(context, List.of(independent), false);
                    consumer.executeGroup(context, stage, pass, 0, List.of(independent), false);
                    consumer.finishExecute(context);
                    check(CombinedCapeHost.drawn.size() == before + 1, "Normal-close subsequent preparation failed");
                }
            }
            check(producer.profileReads.get() == 1 && producer.imageReads.get() == 1, "Consumer caused provider I/O");
            check(independentToken.isValid(), "Independent owner invalidated");
            System.out.println("COMBINED_COMPOSITION_PASS scope=" + args[1] + " scenario=" + scenario + " order=" + args[3] + " stage=" + args[4] + " style=" + args[5] + " geometry=" + CombinedCapeHost.geometry + " draws=" + CombinedCapeHost.drawn.size() + "; raw material snapshot consumer scope, no publication/GPU claim");
        }
    }
    public static void initializeRenderOptions() throws Exception {
        GameRenderer game = allocate(GameRenderer.class);
        var gameState = allocate(net.minecraft.client.renderer.state.GameRenderState.class);
        var options = new net.minecraft.client.renderer.state.OptionsRenderState();
        options.improvedTransparency = true;
        set(gameState, "optionsRenderState", options);
        set(gameState, "levelRenderState", allocate(net.minecraft.client.renderer.state.level.LevelRenderState.class));
        set(game, "gameRenderState", gameState); set(Minecraft.getInstance(), "gameRenderer", game);
    }
    public static List<CustomFeatureRenderer.Submit> drain(SubmitNodeStorage storage) {
        List<CustomFeatureRenderer.Submit> result = new ArrayList<>();
        storage.drainPhases(phase -> phase.sortInto(new FeatureRenderPhase.Output() {
            public void accept(SubmitNode node, boolean ordered) { throw new AssertionError("Unexpected singleton"); }
            public <T extends SubmitNode> void acceptFeatureGroup(FeatureRendererType<T> type, Collection<T> nodes, boolean ordered) {
                for (T node : nodes) result.add((CustomFeatureRenderer.Submit) node);
            }
        }));
        return result;
    }
    public static <T> T allocate(Class<T> type) throws Exception {
        var field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe"); field.setAccessible(true);
        Object unsafe = field.get(null); return type.cast(unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type));
    }
    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
