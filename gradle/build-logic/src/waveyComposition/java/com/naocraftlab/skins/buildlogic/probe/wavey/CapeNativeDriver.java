package com.naocraftlab.skins.buildlogic.probe.wavey;

import java.lang.reflect.*;
import java.util.Map;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.*;

public final class CapeNativeDriver implements AutoCloseable {
    public final FeatureRenderDispatcher dispatcher;
    public final FeatureRenderDispatcher.PreparedFrame frame;
    public final FeatureFrameContext context;
    public final Method prepare;
    public CapeNativeDriver(CustomFeatureRenderer consumer) throws Exception {
        this(renderers(consumer));
    }
    private static FeatureRendererMap renderers(CustomFeatureRenderer consumer) {
        FeatureRendererMap map = new FeatureRendererMap(); map.put(CustomFeatureRenderer.TYPE, consumer); return map;
    }
    public CapeNativeDriver(FeatureRendererMap renderers) throws Exception {
        context = new FeatureFrameContext(null, null, null, null, null, null, null, CapeStaged.headless());
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        dispatcher = (FeatureRenderDispatcher) unsafe.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), FeatureRenderDispatcher.class);
        frame = dispatcher.new PreparedFrame();
        for (var entry : Map.of("featureRenderers", (Object) renderers, "preparedFrame", frame, "stagedVertexBuffer", context.stagedVertexBuffer()).entrySet()) {
            Field field = FeatureRenderDispatcher.class.getDeclaredField(entry.getKey()); field.setAccessible(true); field.set(dispatcher, entry.getValue());
        }
        prepare = FeatureRenderDispatcher.class.getDeclaredMethod("prepareFrameWithContext", FeatureFrameContext.class, SubmitNodeStorage.class);
        prepare.setAccessible(true);
    }
    public void drain(SubmitNodeStorage storage) throws Exception {
        if (prepare.invoke(dispatcher, context, storage) != frame) throw new AssertionError("NATIVE_FRAME_IDENTITY");
        try {
            frame.executeSolid(); frame.executeTranslucent(); frame.executeOutline();
            frame.executeTranslucentAfterTerrain(); frame.executeAlwaysOnTop();
        } finally { frame.close(); }
    }
    @Override public void close() { dispatcher.close(); }
}
