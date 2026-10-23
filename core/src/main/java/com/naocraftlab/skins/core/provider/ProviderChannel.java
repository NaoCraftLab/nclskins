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
        ProviderObservation<T> sneaky,
        ProviderObservation<T> minecraftcapes,
        CpmConfiguration cpmConfiguration,
        List<String> serializedOrder) {
    public ProviderChannel(List<BuiltinProvider> order, ProviderObservation<T> offline,
            ProviderObservation<T> minecraft, long configurationRevision, long intentRevision,
            T desired, ProviderDelivery minecraftDelivery, T offlineDesired,
            ProviderObservation<T> optifine, ProviderObservation<T> skinmc,
            ProviderObservation<T> sneaky, ProviderObservation<T> minecraftcapes,
            CpmConfiguration cpmConfiguration) {
        this(order, offline, minecraft, configurationRevision, intentRevision, desired,
                minecraftDelivery, offlineDesired, optifine, skinmc, sneaky, minecraftcapes,
                cpmConfiguration, order.stream().map(BuiltinProvider::name).toList());
    }

    public ProviderChannel(List<BuiltinProvider> order, ProviderObservation<T> offline,
            ProviderObservation<T> minecraft, long configurationRevision, long intentRevision,
            T desired, ProviderDelivery minecraftDelivery, T offlineDesired,
            ProviderObservation<T> optifine, ProviderObservation<T> skinmc,
            ProviderObservation<T> sneaky, ProviderObservation<T> minecraftcapes) {
        this(order, offline, minecraft, configurationRevision, intentRevision, desired,
                minecraftDelivery, offlineDesired, optifine, skinmc, sneaky, minecraftcapes, null);
    }

    public ProviderChannel(List<BuiltinProvider> order, ProviderObservation<T> offline,
            ProviderObservation<T> minecraft, long configurationRevision, long intentRevision,
            T desired, ProviderDelivery minecraftDelivery, T offlineDesired,
            ProviderObservation<T> optifine, ProviderObservation<T> skinmc,
            ProviderObservation<T> sneaky) {
        this(order, offline, minecraft, configurationRevision, intentRevision, desired,
                minecraftDelivery, offlineDesired, optifine, skinmc, sneaky,
                ProviderObservation.unknown());
    }
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
        serializedOrder = List.copyOf(serializedOrder);
        if (serializedOrder.size() > 33
                || serializedOrder.stream().filter(id -> !BuiltinProvider.CPM.name().equals(id)).count() > 32
                || serializedOrder.stream().distinct().count() != serializedOrder.size()
                || serializedOrder.stream().anyMatch(id -> !id.matches("[A-Z][A-Z0-9_]{0,63}"))
                || !knownOrder(serializedOrder).equals(order)) {
            throw new IllegalArgumentException("Invalid serialized provider order");
        }
        if (order.size() > BuiltinProvider.values().length
                || order.stream().distinct().count() != order.size()) {
            throw new IllegalArgumentException("Provider order contains duplicate entries");
        }
        Objects.requireNonNull(offline, "offline");
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(optifine, "optifine");
        Objects.requireNonNull(skinmc, "skinmc");
        Objects.requireNonNull(sneaky, "sneaky");
        Objects.requireNonNull(minecraftcapes, "minecraftcapes");
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

    public ProviderChannel<T> initializeCpm() {
        if (cpmConfiguration != null) return this;
        List<String> full = new ArrayList<>(serializedOrder);
        if (!enabled(BuiltinProvider.CPM)) full.add(0, BuiltinProvider.CPM.name());
        List<BuiltinProvider> updated = knownOrder(full);
        return new ProviderChannel<>(updated, offline, minecraft,
                Math.incrementExact(configurationRevision), intentRevision, desired, minecraftDelivery,
                offlineDesired, optifine, skinmc, sneaky, minecraftcapes, new CpmConfiguration(null), full);
    }

    public record CpmConfiguration(Integer disabledSlot) {
        public CpmConfiguration {
            if (disabledSlot != null && disabledSlot < 0) {
                throw new IllegalArgumentException("Invalid optional provider slot");
            }
        }
    }

    public boolean enabled(BuiltinProvider provider) {
        return order.contains(Objects.requireNonNull(provider, "provider"));
    }

    public ProviderObservation<T> observation(BuiltinProvider provider) {
        return switch (provider) {
            case CPM -> ProviderObservation.unknown();
            case OFFLINE -> offline;
            case MINECRAFT -> minecraft;
            case OPTIFINE -> optifine;
            case SKINMC -> skinmc;
            case SNEAKY -> sneaky;
            case MINECRAFTCAPES -> minecraftcapes;
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
                : minecraftDelivery, localValue, optifine, skinmc, sneaky, minecraftcapes, cpmConfiguration, serializedOrder);
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
                localValue, optifine, skinmc, sneaky, minecraftcapes, cpmConfiguration, serializedOrder);
    }

    public ProviderChannel<T> enable(BuiltinProvider provider) {
        if (enabled(provider)) {
            return this;
        }
        List<String> full = new ArrayList<>(serializedOrder);
        full.add(provider.name());
        List<BuiltinProvider> updated = knownOrder(full);
        long generation = Math.incrementExact(configurationRevision);
        T current = intentRevision > 0 ? (provider == BuiltinProvider.OFFLINE ? offlineDesired : desired) : enabled(BuiltinProvider.MINECRAFT) ? minecraft.value() : null;
        return new ProviderChannel<>(updated,
                provider == BuiltinProvider.OFFLINE ? ProviderObservation.observed(current) : offline,
                minecraft, generation, intentRevision, desired,
                provider == BuiltinProvider.MINECRAFT
                        ? minecraftDelivery.assign(intentRevision, generation) : minecraftDelivery,
                offlineDesired, optifine, skinmc, sneaky, minecraftcapes,
                provider == BuiltinProvider.CPM ? new CpmConfiguration(null) : cpmConfiguration, full);
    }

    public ProviderChannel<T> bootstrap(long revision, T value) {
        if (intentRevision != 0) return this;
        ProviderChannel<T> selected = select(revision, value);
        return new ProviderChannel<>(order, offline.known() ? offline : selected.offline(),
                enabled(BuiltinProvider.MINECRAFT) ? ProviderObservation.observed(value) : minecraft,
                configurationRevision, revision, value,
                enabled(BuiltinProvider.MINECRAFT)
                        ? new ProviderDelivery(revision, minecraftDelivery.activation(), ProviderDelivery.Status.CONFIRMED)
                        : selected.minecraftDelivery(), selected.offlineDesired(), optifine, skinmc, sneaky,
                minecraftcapes, cpmConfiguration, serializedOrder);
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
                intentRevision, desired, delivery, offlineDesired, optifine, skinmc, sneaky,
                minecraftcapes, provider == BuiltinProvider.CPM
                        ? new CpmConfiguration(serializedOrder.indexOf(provider.name())) : cpmConfiguration,
                provider == BuiltinProvider.CPM
                        ? serializedOrder.stream().filter(id -> !id.equals(provider.name())).toList()
                        : serializeOrder(updated));
    }

    public List<BuiltinProvider> visibleOrder(boolean cpmPresent) {
        return order.stream().filter(provider -> provider != BuiltinProvider.CPM || cpmPresent).toList();
    }

    public ProviderChannel<T> moveVisible(BuiltinProvider provider, int direction, boolean cpmPresent) {
        if (direction != -1 && direction != 1) {
            throw new IllegalArgumentException("Provider move must be adjacent");
        }
        List<BuiltinProvider> visible = visibleOrder(cpmPresent);
        int index = visible.indexOf(provider);
        int target = index + direction;
        if (index < 0 || target < 0 || target >= visible.size()) return this;
        List<BuiltinProvider> updated = new ArrayList<>(order);
        java.util.Collections.swap(updated, order.indexOf(provider), order.indexOf(visible.get(target)));
        return new ProviderChannel<>(updated, offline, minecraft,
                Math.incrementExact(configurationRevision), intentRevision, desired, minecraftDelivery,
                offlineDesired, optifine, skinmc, sneaky, minecraftcapes, cpmConfiguration, serializeOrder(updated));
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
                offlineDesired, optifine, skinmc, sneaky, minecraftcapes, cpmConfiguration, serializeOrder(updated));
    }

    public ProviderChannel<T> observeMinecraft(T value) {
        if (!enabled(BuiltinProvider.MINECRAFT)) {
            return this;
        }
        ProviderObservation<T> initialOffline = !offline.known() && enabled(BuiltinProvider.OFFLINE) && intentRevision == 0
                ? ProviderObservation.observed(value) : offline;
        return new ProviderChannel<>(order, initialOffline, ProviderObservation.observed(value),
                configurationRevision, intentRevision, desired, minecraftDelivery, offlineDesired,
                optifine, skinmc, sneaky, minecraftcapes, cpmConfiguration, serializedOrder);
    }

    public ProviderChannel<T> observeOptifine(T value) {
        if (!enabled(BuiltinProvider.OPTIFINE)) {
            return this;
        }
        return new ProviderChannel<>(order, offline, minecraft, configurationRevision,
                intentRevision, desired, minecraftDelivery, offlineDesired,
                ProviderObservation.observed(value), skinmc, sneaky, minecraftcapes, cpmConfiguration, serializedOrder);
    }

    public ProviderChannel<T> observeSkinmc(T value) {
        if (!enabled(BuiltinProvider.SKINMC)) {
            return this;
        }
        return new ProviderChannel<>(order, offline, minecraft, configurationRevision,
                intentRevision, desired, minecraftDelivery, offlineDesired, optifine,
                ProviderObservation.observed(value), sneaky, minecraftcapes, cpmConfiguration, serializedOrder);
    }

    public ProviderChannel<T> observeSneaky(T value) {
        if (!enabled(BuiltinProvider.SNEAKY)) return this;
        return withSneakyObservation(ProviderObservation.observed(value));
    }

    public ProviderChannel<T> withSneakyObservation(ProviderObservation<T> observation) {
        return new ProviderChannel<>(order, offline, minecraft, configurationRevision,
                intentRevision, desired, minecraftDelivery, offlineDesired, optifine,
                skinmc, observation, minecraftcapes, cpmConfiguration, serializedOrder);
    }

    public ProviderChannel<T> observeMinecraftcapes(T value) {
        if (!enabled(BuiltinProvider.MINECRAFTCAPES)) return this;
        return new ProviderChannel<>(order, offline, minecraft, configurationRevision,
                intentRevision, desired, minecraftDelivery, offlineDesired, optifine,
                skinmc, sneaky, ProviderObservation.observed(value), cpmConfiguration, serializedOrder);
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
                offlineDesired, optifine, skinmc, sneaky, minecraftcapes, cpmConfiguration, serializedOrder);
    }

    private static List<BuiltinProvider> knownOrder(List<String> serialized) {
        List<BuiltinProvider> known = new ArrayList<>();
        for (String id : serialized) {
            for (BuiltinProvider provider : BuiltinProvider.values()) {
                if (provider.name().equals(id)) known.add(provider);
            }
        }
        return List.copyOf(known);
    }

    private List<String> serializeOrder(List<BuiltinProvider> updated) {
        List<String> result = new ArrayList<>();
        int next = 0;
        for (String id : serializedOrder) {
            if (knownOrder(List.of(id)).isEmpty()) result.add(id);
            else if (next < updated.size()) result.add(updated.get(next++).name());
        }
        while (next < updated.size()) result.add(updated.get(next++).name());
        return List.copyOf(result);
    }

    public record Resolved<T>(BuiltinProvider provider, T value) {
        public Resolved {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(value, "value");
        }
    }
}
