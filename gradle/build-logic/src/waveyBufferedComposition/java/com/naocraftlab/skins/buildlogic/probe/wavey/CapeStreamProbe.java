package com.naocraftlab.skins.compat.waveycapes;

import com.mojang.blaze3d.vertex.*;
import com.naocraftlab.skins.buildlogic.probe.wavey.*;
import com.naocraftlab.skins.client.TextureUseValidity;
import dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper;
import dev.tr7zw.waveycapes.render.*;
import dev.tr7zw.waveycapes.support.*;
import java.lang.reflect.Proxy;
import java.util.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.resources.Identifier;

public final class CapeStreamProbe {
    private static int clock;
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static final class Buffers extends MultiBufferSource.BufferSource implements AutoCloseable {
        final ByteBufferBuilder fixed;
        final ByteBufferBuilder shared;
        final List<List<String>> streams = new ArrayList<>();
        Buffers(ByteBufferBuilder shared, ByteBufferBuilder fixed) {
            super(shared, new LinkedHashMap<>(Map.of(RenderTypes.entityGlint(), fixed)));
            this.shared = shared; this.fixed = fixed;
            check(fixedBuffers.get(RenderTypes.entityGlint()) == fixed && fixed != null, "NONNULL_FIXED_REFERENCE_REQUIRED");
        }
        public VertexConsumer getBuffer(RenderType type) {
            VertexConsumer nativeConsumer = super.getBuffer(type);
            List<String> stream = new ArrayList<>(); streams.add(stream);
            return (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class},
                    (proxy, method, args) -> {
                        stream.add(method.getName() + Arrays.toString(args));
                        if (method.getName().equals("addVertex") && args.length == 3) method.invoke(nativeConsumer, args);
                        return method.getReturnType() == void.class ? null : proxy;
                    });
        }
        public void close() { shared.close(); fixed.close(); }
    }
    private static List<List<String>> consume(List<CapePass> passes) throws Exception {
        clock = 0;
        BufferedCapeHost.drawBoundary = () -> clock++;
        BufferedCapeHost.geometrySink = supplied -> {
            for (int i = 0; i < 4; i++) supplied.addVertex(clock + i * .25f, i, -i)
                    .setColor(17 + i, 33, 65, 127).setUv(i / 4f, .75f).setUv1(7, 11).setUv2(13, 17)
                    .setNormal(.125f, .25f, .5f).setLineWidth(2);
        };
        CapeRenderer renderer = new CapeRenderer() {
            public CapeInfos getCapeInfo(PlayerWrapper actor) { throw new AssertionError("LEGACY_PATH"); }
            public List<CapePass> getCapePasses(PlayerWrapper actor) { return passes; }
        };
        ModSupport support = new ModSupport() {
            public boolean shouldBeUsed(PlayerWrapper actor) { return true; }
            public CapeRenderer getRenderer() { return renderer; }
            public int priority() { return 1; }
            public boolean blockFeatureRenderer(Object actor) { return false; }
        };
        check(SupportManager.getSupportedMods().add(support), "SUPPORT_ADD");
        try (Buffers buffers = new Buffers(new ByteBufferBuilder(1024), new ByteBufferBuilder(1024))) {
            SubmitNodeStorage storage = new SubmitNodeStorage();
            new CapeNodeCollector().submitCape(storage, null, new PoseStack(), 157, .5f);
            CustomFeatureRenderer consumer = new CustomFeatureRenderer();
            for (var collection : storage.getSubmitsPerOrder().values()) {
                for (String method : List.of("render", "renderSolid", "renderTranslucent")) {
                    try { CustomFeatureRenderer.class.getMethod(method, SubmitNodeCollection.class, MultiBufferSource.BufferSource.class)
                            .invoke(consumer, collection, buffers); }
                    catch (NoSuchMethodException otherAbi) { }
                }
            }
            buffers.endBatch();
            check(clock == 2, "ACTUAL_DRAW_BOUNDARY_NOT_REACHED " + clock);
            check(buffers.streams.size() == 2 && !buffers.streams.getFirst().isEmpty(), "NONEMPTY_STREAMS_REQUIRED");
            return buffers.streams;
        } finally {
            SupportManager.getSupportedMods().remove(support);
            BufferedCapeHost.drawBoundary = null; BufferedCapeHost.geometrySink = null;
            BufferedCapeHost.verifyClosed();
        }
    }
    public static void main(String[] args) throws Exception {
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread(); run();
    }
    private static VertexConsumer sink(List<String> events, RuntimeException failure) {
        return (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class},
                (proxy, method, args) -> {
                    if (failure != null) throw failure;
                    events.add(method.getName() + Arrays.toString(args));
                    return method.getReturnType() == void.class ? null : proxy;
                });
    }
    private static void controls(Identifier texture) {
        boolean[] live = {true};
        var a = WaveyCapePasses.plan(texture, () -> live[0]);
        var b = WaveyCapePasses.plan(texture, () -> true);
        List<String> baseA = new ArrayList<>(), baseB = new ArrayList<>(), foilA = new ArrayList<>(), foilB = new ArrayList<>();
        var oldA = a.get(0).wrapper().apply(sink(baseA, null));
        var freshB = b.get(0).wrapper().apply(sink(baseB, null));
        oldA.addVertex(1, 2, 3).setColor(0x81234567).setUv(.25f, .75f);
        freshB.addVertex(4, 5, 6).setColor(11, 22, 33, 44).setNormal(-.5f, .25f, 1);
        var discard = b.get(1).wrapper().apply(sink(foilB, null));
        discard.addVertex(999, 999, 999).setUv(99, 99);
        a.get(1).wrapper().apply(sink(foilA, null));
        check(baseA.equals(foilA) && baseB.equals(foilB) && !foilA.equals(foilB), "MULTI_PLAN_ISOLATION");
        a.get(1).wrapper().apply(sink(foilA, null));
        check(baseA.equals(foilA), "REPEAT_FOIL_REUSED_STREAM");
        List<String> next = new ArrayList<>(), nextFoil = new ArrayList<>();
        var current = a.get(0).wrapper().apply(sink(next, null));
        oldA.addVertex(77, 77, 77);
        current.addVertex(7, 8, 9);
        a.get(1).wrapper().apply(sink(nextFoil, null));
        check(next.equals(nextFoil), "OLD_WRAPPER_CONTAMINATED_RESET");
        var empty = WaveyCapePasses.plan(texture, () -> true);
        List<String> emptyFoil = new ArrayList<>();
        empty.get(1).wrapper().apply(sink(emptyFoil, null));
        empty.get(0).wrapper().apply(sink(new ArrayList<>(), null));
        empty.get(1).wrapper().apply(sink(emptyFoil, null));
        check(emptyFoil.isEmpty(), "EMPTY_OR_OUT_OF_ORDER_REPLAY");
        var bounded = WaveyCapePasses.plan(texture, () -> true);
        List<String> boundedBase = new ArrayList<>(), boundedFoil = new ArrayList<>();
        var writer = bounded.get(0).wrapper().apply(sink(boundedBase, null));
        for (int i = 0; i <= CapeVertexReplay.MAX_COMMANDS; i++) writer.addVertex(i, 0, 0);
        bounded.get(1).wrapper().apply(sink(boundedFoil, null));
        check(boundedBase.size() == CapeVertexReplay.MAX_COMMANDS + 1 && boundedFoil.isEmpty(), "BOUND_MUST_PRESERVE_BASE_AND_REJECT_PARTIAL_FOIL");
        var stale = a.get(0).wrapper().apply(sink(new ArrayList<>(), null));
        stale.addVertex(1, 1, 1); live[0] = false;
        List<String> staleFoil = new ArrayList<>();
        a.get(1).wrapper().apply(sink(staleFoil, null));
        check(staleFoil.isEmpty(), "RETIRED_STREAM_REPLAYED");
        var exceptional = WaveyCapePasses.plan(texture, () -> true);
        RuntimeException failure = new IllegalStateException("OWNED_CONSUMER_ABORT");
        try { exceptional.get(0).wrapper().apply(sink(new ArrayList<>(), failure)).addVertex(1, 2, 3); throw new AssertionError("BASE_FAILURE_MISSING"); }
        catch (RuntimeException actual) { check(actual == failure, "BASE_FAILURE_CHANGED"); }
        exceptional.get(1).wrapper().apply(sink(staleFoil, null));
        check(staleFoil.isEmpty(), "FAILED_BASE_RETAINED_COMMANDS");
        exceptional.get(0).wrapper().apply(sink(new ArrayList<>(), null)).addVertex(1, 2, 3);
        try { exceptional.get(1).wrapper().apply(sink(new ArrayList<>(), failure)); throw new AssertionError("FOIL_FAILURE_MISSING"); }
        catch (RuntimeException actual) { check(actual == failure, "FOIL_FAILURE_CHANGED"); }
        exceptional.get(1).wrapper().apply(sink(staleFoil, null));
        check(staleFoil.isEmpty(), "FAILED_REPLAY_RETAINED_COMMANDS");
        check(Arrays.stream(CapeVertexReplay.class.getDeclaredFields()).filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers())).noneMatch(field -> VertexConsumer.class.isAssignableFrom(field.getType())), "PLAN_RETAINS_HOST_CONSUMER");
        System.out.println("CAPE_STREAM_CONTROLS_PASS multi-plan/bound/empty/reset/repeat/retirement/base+foil-exceptions/no-host-field");
    }
    public static void run() throws Exception {
        try (HeadlessCapeClient client = HeadlessCapeClient.open(true)) {
            var texture = Identifier.parse("nclskins:synthetic_stream");
            var reference = consume(List.of(new CapePass(RenderTypes.entityTranslucent(texture), java.util.function.UnaryOperator.identity()),
                    new CapePass(RenderTypes.entityGlint(), java.util.function.UnaryOperator.identity())));
            check(reference.get(0).equals(reference.get(1)), "FIXED_REFERENCE_STREAM_MISMATCH");
            System.out.println("NATIVE_FIXED_REFERENCE_STREAM_PASS events=" + reference.getFirst().size());
            BufferedCapeHost.reset();
            var actual = consume(WaveyCapePasses.plan(texture, () -> true));
            check(actual.get(0).equals(actual.get(1)), "CAPTURED_BASE_FOIL_STREAM_MISMATCH base=" + actual.get(0) + " foil=" + actual.get(1));
            System.out.println("CAPTURED_BASE_FOIL_STREAM_PASS events=" + actual.getFirst().size() + "; actual plan/collector/getBuffer/draw boundary; CPU attributes, no GPU");
            controls(texture);
        }
        try (HeadlessCapeClient client = HeadlessCapeClient.open(false)) {
            var plan = WaveyCapePasses.plan(Identifier.parse("nclskins:synthetic_worldless"), () -> true);
            var consumer = sink(new ArrayList<>(), null);
            check(plan.size() == 1 && plan.getFirst().wrapper().apply(consumer) == consumer, "WORLDLESS_WRAPPER_CHANGED");
        }
    }
}
