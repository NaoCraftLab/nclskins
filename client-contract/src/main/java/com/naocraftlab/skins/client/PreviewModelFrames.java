package com.naocraftlab.skins.client;

import java.util.Objects;
import java.util.function.Function;

public final class PreviewModelFrames {
    private static volatile Function<PreviewRenderer.PreviewAppearance, Lease> frames = appearance -> () -> {};
    private static volatile java.util.function.BiFunction<Object, PreviewRenderer.PreviewAppearance, Body> preparation =
            (profile, appearance) -> new Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true);

    private static volatile java.util.function.BiFunction<Object, PreviewRenderer.PreviewAppearance, NeutralBody> neutral =
            (profile, appearance) -> null;

    public static void neutralWith(java.util.function.BiFunction<Object, PreviewRenderer.PreviewAppearance, NeutralBody> factory) {
        neutral = Objects.requireNonNull(factory);
    }

    public static NeutralBody neutral(Object profile, PreviewRenderer.PreviewAppearance appearance) {
        return neutral.apply(profile, appearance);
    }

    public interface NeutralBody extends AutoCloseable {
        Body body();
        Lease open(boolean reference);
        @Override void close();
    }

    private PreviewModelFrames() {}

    public static void install(Function<PreviewRenderer.PreviewAppearance, Lease> factory) { frames = Objects.requireNonNull(factory); }
    public static Lease open(PreviewRenderer.PreviewAppearance appearance) { return frames.apply(appearance); }
    public static void prepareWith(java.util.function.BiFunction<Object, PreviewRenderer.PreviewAppearance, Body> factory) {
        preparation = Objects.requireNonNull(factory);
    }
    public static Body prepare(Object profile, PreviewRenderer.PreviewAppearance appearance) {
        return preparation.apply(profile, appearance);
    }
    public enum Requirement { ABSENT, REQUIRED, DENIED, UNKNOWN }

    public record BodyRequirements(Requirement cpm, Requirement ears) {
        public BodyRequirements { Objects.requireNonNull(cpm); Objects.requireNonNull(ears); }
        public boolean admitted() {
            return (cpm == Requirement.ABSENT || cpm == Requirement.REQUIRED)
                    && (ears == Requirement.ABSENT || ears == Requirement.REQUIRED);
        }
    }

    public record Body(PreviewRenderer.BodyStatus status, java.util.function.BooleanSupplier current) {
        public Body { Objects.requireNonNull(status); Objects.requireNonNull(current); }
    }
    public interface Completion extends java.util.function.Consumer<Body> {
        boolean current();
        default boolean currentInput(String location) { return current(); }
        default boolean needsOwnInputPreparation() { return false; }
        default boolean reusesConfirmedOutput() { return false; }
        default boolean requiresConsumption() { return false; }
    }
    public static java.util.function.Consumer<Body> mapStatus(java.util.function.Consumer<Body> consumer,
            java.util.function.UnaryOperator<PreviewRenderer.BodyStatus> mapping) {
        Objects.requireNonNull(consumer);
        Objects.requireNonNull(mapping);
        if (!(consumer instanceof Completion completion))
            return body -> consumer.accept(new Body(mapping.apply(body.status()), body.current()));
        return new Completion() {
            public boolean current() { return completion.current(); }
            public boolean currentInput(String location) { return completion.currentInput(location); }
            public boolean needsOwnInputPreparation() { return completion.needsOwnInputPreparation(); }
            public boolean reusesConfirmedOutput() { return completion.reusesConfirmedOutput(); }
            public boolean requiresConsumption() { return completion.requiresConsumption(); }
            public void accept(Body body) {
                completion.accept(new Body(mapping.apply(body.status()), body.current()));
            }
        };
    }
    public interface Lease extends AutoCloseable {
        default Body body() { return new Body(PreviewRenderer.BodyStatus.ORDINARY, () -> true); }
        @Override void close();
    }
}
