package com.naocraftlab.skins.server.plugin.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;


public final class ExactAdapterSelector<T> {
    private final Map<ServerRuntimeIdentity, Supplier<? extends T>> registrations;

    public ExactAdapterSelector(Map<ServerRuntimeIdentity, Supplier<? extends T>> registrations) {
        Objects.requireNonNull(registrations, "registrations");
        if (registrations.isEmpty()) {
            throw new IllegalArgumentException("At least one exact adapter registration is required");
        }
        Map<ServerRuntimeIdentity, Supplier<? extends T>> checked = new LinkedHashMap<>();
        registrations.forEach((identity, factory) -> {
            if (checked.put(
                    Objects.requireNonNull(identity, "identity"),
                    Objects.requireNonNull(factory, "factory")) != null) {
                throw new IllegalArgumentException("Duplicate exact adapter " + identity);
            }
        });
        this.registrations = Map.copyOf(checked);
    }

    public Selection<T> select(ServerRuntimeIdentity identity) {
        Supplier<? extends T> factory = registrations.get(
                Objects.requireNonNull(identity, "identity"));
        if (factory == null) {
            return Selection.unsupported(identity);
        }
        return Selection.supported(identity, factory);
    }

    public static final class Selection<T> {
        private final ServerRuntimeIdentity identity;
        private final Supplier<? extends T> factory;
        private T adapter;

        private Selection(ServerRuntimeIdentity identity, Supplier<? extends T> factory) {
            this.identity = identity;
            this.factory = factory;
        }

        private static <T> Selection<T> supported(
                ServerRuntimeIdentity identity,
                Supplier<? extends T> factory) {
            return new Selection<>(identity, factory);
        }

        private static <T> Selection<T> unsupported(ServerRuntimeIdentity identity) {
            return new Selection<>(identity, null);
        }

        public boolean supported() {
            return factory != null;
        }

        public ServerRuntimeIdentity identity() {
            return identity;
        }

        public synchronized T load() {
            if (factory == null) {
                throw new UnsupportedOperationException(
                        "NCL Skins Plugin has no exact adapter for "
                                + identity.minecraftVersion() + "/" + identity.family().id()
                                + "/" + identity.threadingModel().name().toLowerCase(
                                java.util.Locale.ROOT));
            }
            if (adapter == null) {
                adapter = Objects.requireNonNull(factory.get(), "adapter factory result");
            }
            return adapter;
        }
    }
}
