package com.naocraftlab.skins.client;

import java.util.Objects;
import java.util.function.Function;

public final class PreviewModelFrames {
    private static volatile Function<PreviewRenderer.PreviewAppearance, Lease> frames = appearance -> () -> {};
    private static volatile java.util.function.BiFunction<Object, PreviewRenderer.PreviewAppearance, Body> preparation =
            (profile, appearance) -> new Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true);

    private PreviewModelFrames() {}

    public static void install(Function<PreviewRenderer.PreviewAppearance, Lease> factory) { frames = Objects.requireNonNull(factory); }
    public static Lease open(PreviewRenderer.PreviewAppearance appearance) { return frames.apply(appearance); }
    public static void prepareWith(java.util.function.BiFunction<Object, PreviewRenderer.PreviewAppearance, Body> factory) {
        preparation = Objects.requireNonNull(factory);
    }
    public static Body prepare(Object profile, PreviewRenderer.PreviewAppearance appearance) {
        return preparation.apply(profile, appearance);
    }
    public record Body(PreviewRenderer.BodyStatus status, java.util.function.BooleanSupplier current) {
        public Body { Objects.requireNonNull(status); Objects.requireNonNull(current); }
    }
    public interface Completion extends java.util.function.Consumer<Body> {
        boolean current();
    }
    public interface Lease extends AutoCloseable {
        default Body body() { return new Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true); }
        @Override void close();
    }
}
