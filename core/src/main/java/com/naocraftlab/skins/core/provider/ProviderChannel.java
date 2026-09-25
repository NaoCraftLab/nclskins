package com.naocraftlab.skins.core.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;

public record ProviderChannel<T>(
        List<BuiltinProvider> order,
        ProviderObservation<T> offline,
        ProviderObservation<T> minecraft,
        long configurationRevision,
        long intentRevision,
        T desired,
        ProviderDelivery minecraftDelivery,
        T offlineDesired,
        ProviderObservation<T> optifine,
        ProviderObservation<T> skinmc,
        ProviderObservation<T> sneaky) {
    public ProviderChannel(List<BuiltinProvider> order, ProviderObservation<T> offline,
            ProviderObservation<T> minecraft, long configurationRevision, long intentRevision,
            T desired, ProviderDelivery minecraftDelivery, T offlineDesired,
            ProviderObservation<T> optifine, ProviderObservation<T> skinmc) {
        this(order, offline, minecraft, configurationRevision, intentRevision, desired,
                minecraftDelivery, offlineDesired, optifine, skinmc, ProviderObservation.unknown());
    }
    public ProviderChannel(List<BuiltinProvider> order, ProviderObservation<T> offline,
            ProviderObservation<T> minecraft, long configurationRevision, long intentRevision,
            T desired, ProviderDelivery minecraftDelivery, T offlineDesired,
            ProviderObservation<T> optifine) {
        this(order, offline, minecraft, configurationRevision, intentRevision, desired,
                minecraftDelivery, offlineDesired, optifine, ProviderObservation.unknown());
    }
    public ProviderChannel(List<BuiltinProvider> order, ProviderObservation<T> offline,
            ProviderObservation<T> minecraft, long configurationRevision, long intentRevision,
            T desired, ProviderDelivery minecraftDelivery, T offlineDesired) {
        this(order, offline, minecraft, configurationRevision, intentRevision, desired,
                minecraftDelivery, offlineDesired, ProviderObservation.unknown());
    }

    public ProviderChannel(List<BuiltinProvider> order, ProviderObservation<T> offline,
            ProviderObservation<T> minecraft, long configurationRevision, long intentRevision,
            T desired, ProviderDelivery minecraftDelivery) {
        this(order, offline, minecraft, configurationRevision, intentRevision, desired,
                minecraftDelivery, desired, ProviderObservation.unknown());
    }

    public ProviderChannel {
        order = List.copyOf(order);
        if (order.size() > BuiltinProvider.values().length
                || order.stream().distinct().count() != order.size()) {
            throw new IllegalArgumentException("Provider order contains duplicate entries");
        }
        Objects.requireNonNull(offline, "offline");
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(optifine, "optifine");
        Objects.requireNonNull(skinmc, "skinmc");
        Objects.requireNonNull(sneaky, "sneaky");
        Objects.requireNonNull(minecraftDelivery, "minecraftDelivery");
        if (configurationRevision < 0 || intentRevision < 0
                || minecraftDelivery.intentRevision() > intentRevision
                || minecraftDelivery.activation() > configurationRevision) {
            throw new IllegalArgumentException("Invalid provider revisions");
        }
    }

    public static <T> ProviderChannel<T> initial() {
        return new ProviderChannel<>(List.of(BuiltinProvider.OFFLINE, BuiltinProvider.MINECRAFT),
                ProviderObservation.unknown(), ProviderObservation.unknown(), 0, 0, null,
                new ProviderDelivery(0, 0, ProviderDelivery.Status.IDLE));
    }

    public boolean enabled(BuiltinProvider provider) {
        return order.contains(Objects.requireNonNull(provider, "provider"));
    }

    public ProviderObservation<T> observation(BuiltinProvider provider) {
        return switch (provider) {
            case OFFLINE -> offline;
            case MINECRAFT -> minecraft;
            case OPTIFINE -> optifine;
            case SKINMC -> skinmc;
            case SNEAKY -> sneaky;
        };
    }

    public Optional<Resolved<T>> resolve() {
        for (BuiltinProvider provider : order) {
            T value = observation(provider).value();
            if (value != null) {
                return Optional.of(new Resolved<>(provider, value));
            }
        }
        return Optional.empty();
    }

    public ProviderChannel<T> select(long revision, T value) {
        return select(revision, value, value);
    }

    public ProviderChannel<T> select(long revision, T localValue, T value) {
        if (revision <= intentRevision) {
            throw new IllegalArgumentException("Selection must advance the intent revision");
        }
        return new ProviderChannel<>(order,
                enabled(BuiltinProvider.OFFLINE) ? ProviderObservation.observed(localValue) : offline,
                minecraft, configurationRevision, revision, value,
                enabled(BuiltinProvider.MINECRAFT)
                        ? minecraftDelivery.assign(revision, minecraftDelivery.activation())
                        : minecraftDelivery, localValue, optifine, skinmc, sneaky);
    }

    public ProviderChannel<T> selectMatchingObservation(
            long revision, T localValue, T value, BiPredicate<T, T> matches) {
        boolean confirmedMatch = enabled(BuiltinProvider.MINECRAFT)
                && value != null
                && minecraftDelivery.status() == ProviderDelivery.Status.CONFIRMED
                && minecraftDelivery.activation() == configurationRevision
                && minecraft.known()
                && minecraft.value() != null
                && matches.test(minecraft.value(), value);
        return revise(revision, localValue, value, !confirmedMatch);
    }

    public ProviderChannel<T> revise(long revision, T localValue, T value, boolean assignMinecraft) {
        if (revision <= intentRevision) {
            throw new IllegalArgumentException("Revision must advance the intent revision");
        }
        return new ProviderChannel<>(order,
                enabled(BuiltinProvider.OFFLINE) ? ProviderObservation.observed(localValue) : offline,
                minecraft, configurationRevision, revision, value,
                enabled(BuiltinProvider.MINECRAFT) && assignMinecraft
                        ? minecraftDelivery.assign(revision, minecraftDelivery.activation())
                        : minecraftDelivery,
                localValue, optifine, skinmc, sneaky);
    }

    public ProviderChannel<T> enable(BuiltinProvider provider) {
        if (enabled(provider)) {
            return this;
        }
        List<BuiltinProvider> updated = new ArrayList<>(order);
        updated.add(provider);
        long generation = Math.incrementExact(configurationRevision);
        T current = intentRevision > 0 ? (provider == BuiltinProvider.OFFLINE ? offlineDesired : desired) : enabled(BuiltinProvider.MINECRAFT) ? minecraft.value() : null;
        return new ProviderChannel<>(updated,
                provider == BuiltinProvider.OFFLINE ? ProviderObservation.observed(current) : offline,
                minecraft, generation, intentRevision, desired,
                provider == BuiltinProvider.MINECRAFT
                        ? minecraftDelivery.assign(intentRevision, generation) : minecraftDelivery,
                offlineDesired, optifine, skinmc, sneaky);
    }

    public ProviderChannel<T> bootstrap(long revision, T value) {
        if (intentRevision != 0) return this;
        ProviderChannel<T> selected = select(revision, value);
        return new ProviderChannel<>(order, offline.known() ? offline : selected.offline(),
                enabled(BuiltinProvider.MINECRAFT) ? ProviderObservation.observed(value) : minecraft,
                configurationRevision, revision, value,
                enabled(BuiltinProvider.MINECRAFT)
                        ? new ProviderDelivery(revision, minecraftDelivery.activation(), ProviderDelivery.Status.CONFIRMED)
                        : selected.minecraftDelivery(), selected.offlineDesired(), optifine, skinmc, sneaky);
    }

    public ProviderChannel<T> disable(BuiltinProvider provider) {
        if (!enabled(provider)) {
            return this;
        }
        List<BuiltinProvider> updated = new ArrayList<>(order);
        updated.remove(provider);
        long generation = Math.incrementExact(configurationRevision);
        ProviderDelivery delivery = provider == BuiltinProvider.MINECRAFT
                ? new ProviderDelivery(minecraftDelivery.intentRevision(), generation,
                        minecraftDelivery.status() == ProviderDelivery.Status.ATTEMPTING
                                ? ProviderDelivery.Status.UNKNOWN : minecraftDelivery.status())
                : minecraftDelivery;
        return new ProviderChannel<>(updated, offline, minecraft, generation,
                intentRevision, desired, delivery, offlineDesired, optifine, skinmc, sneaky);
    }

    public ProviderChannel<T> move(BuiltinProvider provider, int direction) {
        if (direction != -1 && direction != 1) {
            throw new IllegalArgumentException("Provider move must be adjacent");
        }
        int index = order.indexOf(provider);
        int target = index + direction;
        if (index < 0 || target < 0 || target >= order.size()) {
            return this;
        }
        List<BuiltinProvider> updated = new ArrayList<>(order);
        java.util.Collections.swap(updated, index, target);
        return new ProviderChannel<>(updated, offline, minecraft,
                Math.incrementExact(configurationRevision), intentRevision, desired, minecraftDelivery,
                offlineDesired, optifine, skinmc, sneaky);
    }

    public ProviderChannel<T> observeMinecraft(T value) {
        if (!enabled(BuiltinProvider.MINECRAFT)) {
            return this;
        }
        ProviderObservation<T> initialOffline = !offline.known() && enabled(BuiltinProvider.OFFLINE) && intentRevision == 0
                ? ProviderObservation.observed(value) : offline;
        return new ProviderChannel<>(order, initialOffline, ProviderObservation.observed(value),
                configurationRevision, intentRevision, desired, minecraftDelivery, offlineDesired,
                optifine, skinmc, sneaky);
    }

    public ProviderChannel<T> observeOptifine(T value) {
        if (!enabled(BuiltinProvider.OPTIFINE)) {
            return this;
        }
        return new ProviderChannel<>(order, offline, minecraft, configurationRevision,
                intentRevision, desired, minecraftDelivery, offlineDesired,
                ProviderObservation.observed(value), skinmc, sneaky);
    }

    public ProviderChannel<T> observeSkinmc(T value) {
        if (!enabled(BuiltinProvider.SKINMC)) {
            return this;
        }
        return new ProviderChannel<>(order, offline, minecraft, configurationRevision,
                intentRevision, desired, minecraftDelivery, offlineDesired, optifine,
                ProviderObservation.observed(value), sneaky);
    }

    public ProviderChannel<T> observeSneaky(T value) {
        if (!enabled(BuiltinProvider.SNEAKY)) return this;
        return withSneakyObservation(ProviderObservation.observed(value));
    }

    public ProviderChannel<T> withSneakyObservation(ProviderObservation<T> observation) {
        return new ProviderChannel<>(order, offline, minecraft, configurationRevision,
                intentRevision, desired, minecraftDelivery, offlineDesired, optifine,
                skinmc, observation);
    }

    public ProviderChannel<T> settle(
            ProviderDelivery expected, ProviderDelivery.Status status, T observed) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(status, "status");
        if (!enabled(BuiltinProvider.MINECRAFT) || !minecraftDelivery.equals(expected)) {
            return this;
        }
        return new ProviderChannel<>(order, offline,
                status == ProviderDelivery.Status.CONFIRMED
                        ? ProviderObservation.observed(observed) : minecraft,
                configurationRevision, intentRevision, desired, minecraftDelivery.withStatus(status),
                offlineDesired, optifine, skinmc, sneaky);
    }

    public record Resolved<T>(BuiltinProvider provider, T value) {
        public Resolved {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(value, "value");
        }
    }
}
