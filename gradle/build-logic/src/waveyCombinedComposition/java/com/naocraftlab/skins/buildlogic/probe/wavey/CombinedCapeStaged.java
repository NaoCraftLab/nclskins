package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.client.renderer.StagedVertexBuffer;

public final class CombinedCapeStaged extends StagedVertexBuffer {
    private static final Map<Draw, Integer> counts = new IdentityHashMap<>();
    public CombinedCapeStaged() { super(() -> "headless", 1); }
    public static void reset() { counts.clear(); }
    public static boolean empty() { return counts.isEmpty(); }
    public static CombinedCapeStaged open() throws Exception { reset(); return CombinedCapeProbe.allocate(CombinedCapeStaged.class); }
    @Override public Draw appendDraw(VertexFormat format, PrimitiveTopology topology, VertexSorting sorting) {
        try {
            var constructor = Draw.class.getDeclaredConstructor(VertexFormat.class, PrimitiveTopology.class, VertexSorting.class);
            constructor.setAccessible(true);
            Draw draw = constructor.newInstance(format, topology, sorting); counts.put(draw, 0); return draw;
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    @Override public VertexConsumer getVertexBuilder(Draw draw) {
        VertexConsumer consumer = (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
            if (method.getName().equals("addVertex")) counts.compute(draw, (key, count) -> count + 1);
            return method.getReturnType() == VertexConsumer.class ? proxy : null;
        });
        CombinedCapeHost.currentConsumer = consumer; return consumer;
    }
    @Override public void requestIndexCount(Draw draw) {}
    @Override public void endDraw() {}
    @Override public ExecuteInfo getExecuteInfo(Draw draw) {
        ExecuteInfo info = new ExecuteInfo(null, null, null, 0, 0, counts.get(draw), PrimitiveTopology.QUADS); CombinedCapeHost.infos.add(info); return info;
    }
}
