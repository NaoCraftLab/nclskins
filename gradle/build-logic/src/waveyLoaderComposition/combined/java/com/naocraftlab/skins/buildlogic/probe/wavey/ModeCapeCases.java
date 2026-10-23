package com.naocraftlab.skins.buildlogic.probe.wavey;

import java.nio.file.Path;
import java.util.List;

public final class ModeCapeCases {
    public static void reset() { CombinedCapeHost.reset(); CombinedCapeStaged.reset(); }
    public static void verifyReset() {
        if (!CombinedCapeHost.acquired.isEmpty() || !CombinedCapeHost.prepared.isEmpty() || !CombinedCapeHost.drawn.isEmpty()
                || !CombinedCapeHost.infos.isEmpty() || !CombinedCapeHost.renderers.isEmpty() || !CombinedCapeHost.actors.isEmpty()
                || CombinedCapeHost.geometry != 0 || CombinedCapeHost.expectedActor != null || CombinedCapeHost.expectedRenderer != null
                || CombinedCapeHost.currentConsumer != null || CombinedCapeHost.expectedPass != null || CombinedCapeHost.expectedStage != null
                || CombinedCapeHost.geometryFailure != null || CombinedCapeHost.drawFailure != null || CombinedCapeHost.retireAtDraw != null
                || CombinedCapeHost.expectedLight != 31 || CombinedCapeHost.expectedDelta != 0.25F || !CombinedCapeStaged.empty())
            throw new AssertionError("CASE_REFERENCE_COUNTER_RESET");
    }
    public static void verifyClosed() {
        if (CombinedCapeHost.retireAtDraw != null) throw new AssertionError("PENDING_RETIREMENT_REFERENCE");
    }
    public static boolean consumeGuard(net.minecraft.client.renderer.SubmitNodeStorage storage,
            dev.tr7zw.waveycapes.render.CapeRenderer renderer) {
        var pending = CombinedCapeProbe.drain(storage);
        PublicationCapeCollector.consume(pending, null, null, 0, 0);
        if (CombinedCapeHost.geometry == 0 || CombinedCapeHost.drawn.isEmpty()) throw new AssertionError("GUARD_NATIVE_CONSUMER_NOT_REACHED");
        return pending.size() == 1 && com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass.of(pending.getFirst().renderType()) != null
                && CombinedCapeHost.geometry == 1 && CombinedCapeHost.drawn.size() == 1
                && CombinedCapeHost.renderers.size() == 1 && CombinedCapeHost.renderers.contains(renderer);
    }
    public static void run(Path root) throws Exception {
        for (String style : List.of("SMOOTH", "BLOCKY")) ClientCapeBatch.runCase("extraction-" + style,
                () -> ClientCapeBatch.extraction(root.resolve("preview-" + style), style));
        for (String scope : List.of("world", "worldless"))
            for (String order : List.of("foreign-first", "foreign-last"))
                for (String style : List.of("SMOOTH", "BLOCKY"))
                    for (String stage : List.of("normal", "ACCUMULATE", "DEPTH_BOUNDS", "TRANSMITTANCE")) {
                        List<String> states = List.of("normal", "ACCUMULATE").contains(stage)
                                ? List.of("live", "stale-before", "stale-after", "retire-build", "retire-draw", "own-exception", "foreign-exception", "draw-exception")
                                : List.of("live", "stale-after");
                        for (String state : states) {
                            String name = "combined-" + scope + "-" + state + "-" + order + "-" + stage + "-" + style;
                            ClientCapeBatch.runCase(name, () -> CombinedCapeProbe.runCase(new String[]{root.resolve(name).toString(), scope, state, order, stage, style}));
                            if (state.endsWith("exception")) ClientCapeBatch.runCase(name + "-normal-fresh-consumer",
                                    () -> CombinedCapeProbe.runCase(new String[]{root.resolve(name + "-fresh").toString(), scope, "live", order, stage, style}));
                        }
                    }
        for (String mutation : List.of("no-support", "wrong-material")) for (String style : List.of("SMOOTH", "BLOCKY")) {
            String name = "combined-sensitivity-" + mutation + "-" + style;
            ClientCapeBatch.runCase(name, () -> {
                try {
                    CombinedCapeProbe.runCase(new String[]{root.resolve(name).toString(), "world", mutation, "foreign-first", "normal", style});
                    throw new IllegalStateException("COMBINED_SENSITIVITY_ACCEPTED");
                } catch (AssertionError expected) {
                    if (!"COMBINED_INTEGRATION_MISSING".equals(expected.getMessage())) throw expected;
                    System.out.println("CANONICAL_EXPECTED_BEHAVIOR_RED " + name);
                }
            });
        }
    }
}
