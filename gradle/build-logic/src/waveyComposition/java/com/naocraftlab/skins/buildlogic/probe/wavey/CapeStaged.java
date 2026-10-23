package com.naocraftlab.skins.buildlogic.probe.wavey;
import com.mojang.blaze3d.vertex.*;
import com.mojang.blaze3d.PrimitiveTopology;
import net.minecraft.client.renderer.StagedVertexBuffer;
import java.lang.reflect.*;
import java.util.*;
public final class CapeStaged extends StagedVertexBuffer {
    private static final Map<Draw, int[]> counts = new IdentityHashMap<>();
    public static void reset() { counts.clear(); }
    public static boolean empty() { return counts.isEmpty(); }
    public CapeStaged() { super(() -> "not executed", 1); }
    public static CapeStaged headless() throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field f = unsafe.getDeclaredField("theUnsafe"); f.setAccessible(true);
        return (CapeStaged) unsafe.getMethod("allocateInstance", Class.class).invoke(f.get(null), CapeStaged.class);
    }
    @Override public Draw appendDraw(VertexFormat format, PrimitiveTopology topology, VertexSorting sorting) {
        try {
            Constructor<Draw> c = Draw.class.getDeclaredConstructor(VertexFormat.class, PrimitiveTopology.class, VertexSorting.class);
            c.setAccessible(true); Draw draw = c.newInstance(format, topology, sorting); counts.put(draw, new int[1]); return draw;
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    @Override public VertexConsumer getVertexBuilder(Draw draw) {
        return (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(), new Class<?>[]{VertexConsumer.class}, (p, m, a) -> {
            if (m.getName().equals("addVertex")) counts.get(draw)[0]++;
            return m.getReturnType() == VertexConsumer.class ? p : null;
        });
    }
    @Override public void upload() {}
    @Override public void endDraw() {}
    @Override public ExecuteInfo getExecuteInfo(Draw draw) { return new ExecuteInfo(null, null, null, 0, 0, counts.get(draw)[0]); }
}
