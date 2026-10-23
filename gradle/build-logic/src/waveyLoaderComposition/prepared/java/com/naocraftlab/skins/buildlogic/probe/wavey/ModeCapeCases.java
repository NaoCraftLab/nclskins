package com.naocraftlab.skins.buildlogic.probe.wavey;

import java.nio.file.Path;
import java.util.List;

public final class ModeCapeCases {
    public static void reset() { CapeHost.reset(); CapeStaged.reset(); CapeLifetimeProbe.reset(); }
    public static void verifyReset() {
        if (!CapeHost.prepared.isEmpty() || !CapeHost.renderers.isEmpty() || CapeHost.draws != 0 || !CapeStaged.empty()) throw new AssertionError("CASE_REFERENCE_RESET");
    }
    public static void verifyClosed() {
        if (CapeLifetimeProbe.hasPendingRetirement()) throw new AssertionError("PENDING_RETIREMENT_REFERENCE");
    }
    public static boolean consumeGuard(net.minecraft.client.renderer.SubmitNodeStorage storage,
            dev.tr7zw.waveycapes.render.CapeRenderer renderer) throws Exception {
        try (CapeNativeDriver driver = new CapeNativeDriver(new net.minecraft.client.renderer.feature.CustomFeatureRenderer())) { driver.drain(storage); }
        if (CapeHost.geometry == 0 || CapeHost.draws == 0) throw new AssertionError("GUARD_NATIVE_CONSUMER_NOT_REACHED");
        return CapeHost.geometry == 2 && CapeHost.draws == 2 && CapeHost.ownVertices == 2
                && CapeHost.renderers.size() == 1 && CapeHost.renderers.contains(renderer);
    }
    public static void run(Path root) throws Exception {
        for (String style : List.of("SMOOTH", "BLOCKY")) ClientCapeBatch.runCase("extraction-" + style,
                () -> ClientCapeBatch.extraction(root.resolve("preview-" + style), style));
        for (String state : List.of("live", "replace-before", "replace-after", "registry-close-before", "registry-close-after", "close-before", "close-after", "retire-during"))
            for (String order : List.of("foreign-first", "own-first")) ClientCapeBatch.runCase("lifetime-" + state + "-" + order,
                    () -> CapeLifetimeProbe.runCase(new String[]{root.resolve(state + order).toString(), state, order}));
        for (String order : List.of("foreign-first", "own-first")) ClientCapeBatch.runCase("ABA-" + order, () -> CapeAbaProbe.runCase(new String[]{order}));
        for (String ownership : List.of("captured", "unmarked")) {
            ClientCapeBatch.runCase("abort-" + ownership, () -> CapeDriverAbortProbe.runCase(new String[]{root.resolve("abort-" + ownership).toString(), ownership, "abort"}));
            ClientCapeBatch.runCase("normal-fresh-" + ownership, () -> CapeDriverAbortProbe.runCase(new String[]{root.resolve("fresh-" + ownership).toString(), ownership, "normal"}));
        }
    }
}
