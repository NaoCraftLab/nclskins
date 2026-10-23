package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.blaze3d.vertex.*;
import dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper;
import dev.tr7zw.waveycapes.render.CapeRenderer;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.client.renderer.RenderType;

public final class ImmediateCapeHost {
    public static final List<RenderType> acquired = new ArrayList<>(), drawn = new ArrayList<>();
    public static final List<VertexConsumer> consumers = new ArrayList<>();
    public static final List<CapeRenderer> renderers = new ArrayList<>();
    public static RenderType drawing;
    public static VertexConsumer lastReturned;
    public static PoseStack expectedPose;
    public static PlayerWrapper expectedActor;
    public static Runnable afterGeometry = () -> {};
    public static RuntimeException geometryFailure, drawFailure;
    public static int geometries, releases, vertexWrites;

    public static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void reset() {
        acquired.clear(); drawn.clear(); consumers.clear(); renderers.clear();
        drawing = null; lastReturned = null; expectedPose = null; expectedActor = null; geometries = 0; releases = 0; vertexWrites = 0;
        afterGeometry = () -> {}; geometryFailure = null; drawFailure = null;
    }
    public static void geometry(PlayerWrapper actor, CapeRenderer renderer, VertexConsumer consumer,
            PoseStack pose, int light, float delta) {
        boolean inventory = actor.getClass() == PlayerWrapper.class;
        check(actor == expectedActor && pose == expectedPose && light == (inventory ? 15728880 : 157) && delta == (inventory ? 1.0f : 0.25f),
                "Geometry actor/pose/light/delta continuation changed");
        check(consumer == lastReturned, "Collector wrapper replaced the host-owned consumer");
        geometries++; consumers.add(consumer); renderers.add(renderer);
        if (geometryFailure != null) throw geometryFailure;
        ImmediateBuffers.emit(consumer);
        afterGeometry.run();
    }
    public static VertexSorting vertexSorting() { return VertexSorting.DISTANCE_TO_ORIGIN; }
    public static void setup(RenderType type) { check(drawing == null, "Nested terminal"); drawing = type; }
    public static void clear(RenderType type) { check(drawing == type, "Terminal material identity changed"); drawing = null; }
    public static void draw() {
        check(drawing != null, "GPU terminal bypassed native setup continuation");
        if (drawFailure != null) throw drawFailure;
        drawn.add(drawing);
    }
    public static <T> T allocate(Class<T> type) {
        try {
            Class<?> unsafe = Class.forName("sun.misc.Unsafe");
            Field singleton = unsafe.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
            return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    public static Object field(Object object, Class<?> owner, String name) {
        try { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    public static void field(Object object, Class<?> owner, String name, Object value) {
        try { Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
}
