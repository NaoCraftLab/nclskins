package com.naocraftlab.skins.compat.client.identifier.measurement;

import com.naocraftlab.skins.client.PreviewModelFrames;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.PreviewModelNormalization;
import java.util.function.BooleanSupplier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class AvatarBodyCapture implements PreviewModelFrames.Lease {
    private static final ThreadLocal<AvatarBodyCapture> ACTIVE = new ThreadLocal<>();
    private Object state;
    private final BooleanSupplier current;
    private final Thread thread = Thread.currentThread();
    private final AvatarBodyCapture previous;
    private Matrix4f inverse;
    private double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
    private double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
    private boolean invalid, closed;
    private long vertices;

    public AvatarBodyCapture(Object state, BooleanSupplier current) {
        this.state = state;
        this.current = current;
        previous = ACTIVE.get();
        ACTIVE.set(this);
    }

    public static boolean excludes(Object state) {
        var capture = ACTIVE.get();
        return capture != null && capture.state == state && capture.valid();
    }

    public static void root(Object state, Matrix4f root) {
        var capture = ACTIVE.get();
        if (capture != null && capture.state == state && capture.valid()) {
            if (capture.inverse != null) throw new IllegalStateException("Repeated measurement root");
            capture.inverse = new Matrix4f(root).invert();
        }
    }

    public static boolean hiding() {
        var capture = ACTIVE.get();
        return capture != null && !capture.closed && capture.thread == Thread.currentThread();
    }

    public static boolean collecting() {
        var capture = ACTIVE.get();
        return capture != null && capture.inverse != null && capture.valid();
    }

    public static void vertex(float x, float y, float z) {
        var capture = ACTIVE.get();
        if (capture == null || capture.inverse == null || !capture.valid()) return;
        var point = capture.inverse.transformPosition(new Vector3f(x, y, z));
        if (!Float.isFinite(point.x) || !Float.isFinite(point.y) || !Float.isFinite(point.z)) {
            capture.invalid = true;
            return;
        }
        capture.vertices++;
        capture.minX = Math.min(capture.minX, point.x); capture.maxX = Math.max(capture.maxX, point.x);
        capture.minY = Math.min(capture.minY, point.y); capture.maxY = Math.max(capture.maxY, point.y);
        capture.minZ = Math.min(capture.minZ, point.z); capture.maxZ = Math.max(capture.maxZ, point.z);
    }

    private boolean valid() { return !closed && Thread.currentThread() == thread && current.getAsBoolean(); }

    public PreviewRenderer.ModelFitMeasurement ordinary() {
        if (!closed || invalid || vertices == 0 || !current.getAsBoolean() || maxY <= minY) return null;
        double height = maxY - minY;
        return new PreviewRenderer.ModelFitMeasurement(height, (minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2, PreviewModelNormalization.full(height, height,
                (minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2,
                (maxX - minX) / 2, (maxZ - minZ) / 2));
    }

    @Override public void close() {
        if (closed) return;
        if (Thread.currentThread() != thread || ACTIVE.get() != this)
            throw new IllegalStateException("Measurement scope closed out of order");
        closed = true;
        state = null;
        inverse = null;
        if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
    }
}
