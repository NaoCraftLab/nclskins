package com.naocraftlab.skins.client;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;


public final class OuterLayerVisibility {
    private static final OuterLayerVisibility ALL = new OuterLayerVisibility(EnumSet.allOf(OuterLayerPart.class));
    private static final OuterLayerVisibility NONE = new OuterLayerVisibility(EnumSet.noneOf(OuterLayerPart.class));

    private final Set<OuterLayerPart> visibleParts;

    private OuterLayerVisibility(EnumSet<OuterLayerPart> visibleParts) {
        this.visibleParts = Set.copyOf(visibleParts);
    }

    public static OuterLayerVisibility allVisible() {
        return ALL;
    }

    public static OuterLayerVisibility noneVisible() {
        return NONE;
    }

    public static OuterLayerVisibility of(Collection<OuterLayerPart> visibleParts) {
        Objects.requireNonNull(visibleParts, "visibleParts");
        if (visibleParts.isEmpty()) {
            return NONE;
        }
        EnumSet<OuterLayerPart> copy = EnumSet.copyOf(visibleParts);
        return copy.size() == OuterLayerPart.values().length ? ALL : new OuterLayerVisibility(copy);
    }

    public Set<OuterLayerPart> visibleParts() {
        return visibleParts;
    }

    public boolean visible(OuterLayerPart part) {
        return visibleParts.contains(Objects.requireNonNull(part, "part"));
    }

    public boolean allVisible(Collection<OuterLayerPart> parts) {
        Objects.requireNonNull(parts, "parts");
        return visibleParts.containsAll(parts);
    }

    public boolean anyVisible(Collection<OuterLayerPart> parts) {
        Objects.requireNonNull(parts, "parts");
        return parts.stream().anyMatch(visibleParts::contains);
    }

    public OuterLayerVisibility with(OuterLayerPart part, boolean visible) {
        return withAll(Set.of(Objects.requireNonNull(part, "part")), visible);
    }

    public OuterLayerVisibility withAll(Collection<OuterLayerPart> parts, boolean visible) {
        Objects.requireNonNull(parts, "parts");
        EnumSet<OuterLayerPart> next = visibleParts.isEmpty()
                ? EnumSet.noneOf(OuterLayerPart.class)
                : EnumSet.copyOf(visibleParts);
        if (visible) {
            next.addAll(parts);
        } else {
            next.removeAll(parts);
        }
        return of(next);
    }

    public OuterLayerVisibility toggle(OuterLayerPart part) {
        return with(part, !visible(part));
    }


    public OuterLayerVisibility toggleGroup(Collection<OuterLayerPart> parts) {
        Objects.requireNonNull(parts, "parts");
        return withAll(parts, !allVisible(parts));
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof OuterLayerVisibility that && visibleParts.equals(that.visibleParts);
    }

    @Override
    public int hashCode() {
        return visibleParts.hashCode();
    }

    @Override
    public String toString() {
        return "OuterLayerVisibility" + visibleParts;
    }
}
