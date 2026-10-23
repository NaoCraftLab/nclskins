package com.naocraftlab.skins.buildlogic.probe.wavey;

import java.nio.file.Path;
import java.util.List;

public final class ModeCapeCases {
    public static void reset() { BufferedCapeHost.reset(); }
    public static void verifyReset() {
        if (BufferedCapeHost.draws != 0 || BufferedCapeHost.geometry != 0 || !BufferedCapeHost.renderers.isEmpty()) throw new AssertionError("CASE_COUNTER_REFERENCE_RESET");
    }
    public static void verifyClosed() { BufferedCapeHost.verifyClosed(); }
    public static boolean consumeGuard(net.minecraft.client.renderer.SubmitNodeStorage storage,
            dev.tr7zw.waveycapes.render.CapeRenderer renderer) throws Exception {
        BufferedCapeProbe.drain(storage);
        if (BufferedCapeHost.geometry == 0 || BufferedCapeHost.draws == 0) throw new AssertionError("GUARD_NATIVE_CONSUMER_NOT_REACHED");
        return BufferedCapeHost.geometry == 2 && BufferedCapeHost.draws == 2 && BufferedCapeHost.ownVertices == 2
                && BufferedCapeHost.renderers.size() == 1 && BufferedCapeHost.renderers.contains(renderer);
    }
    public static void run(Path root) throws Exception {
        ClientCapeBatch.runCase("cape-stream-coherence", com.naocraftlab.skins.compat.waveycapes.CapeStreamProbe::run);
        if (Boolean.getBoolean("nclskins.wavey.extraction")) {
            ClientCapeBatch.runCase("extraction-publication", () -> ClientCapeBatch.extraction(root.resolve("preview")));
        } else ClientCapeBatch.runCase("preview-configure-only", () -> BufferedPreviewCapeProbe.runCase(new String[]{root.resolve("preview").toString()}));
        for (String state : List.of("live", "before", "after")) ClientCapeBatch.runCase("lifetime-" + state,
                () -> BufferedCapeProbe.runCase(new String[]{root.resolve("lifetime-" + state).toString(), state}));
        ClientCapeBatch.runCase("geometry-abort", () -> {
            BufferedCapeHost.abortGeometry = true;
            try {
                BufferedCapeProbe.runCase(new String[]{root.resolve("abort").toString(), "live"});
                throw new AssertionError("ABORT_NOT_REACHED");
            } catch (java.lang.reflect.InvocationTargetException expected) {
                if (!(expected.getCause() instanceof IllegalStateException) || !"OWNED_GEOMETRY_ABORT".equals(expected.getCause().getMessage())) throw expected;
            }
        });
        ClientCapeBatch.runCase("normal-after-abort-fresh-consumer", () -> BufferedCapeProbe.runCase(new String[]{root.resolve("after-abort").toString(), "live"}));
    }
}
