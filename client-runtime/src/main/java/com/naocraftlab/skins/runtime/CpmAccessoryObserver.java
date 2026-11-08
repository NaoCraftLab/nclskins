package com.naocraftlab.skins.runtime;

public final class CpmAccessoryObserver {
    private final EffectiveSkinSource source;
    private final String name;
    private final long observer;
    private long generation;
    private long modelRevision = -1;

    CpmAccessoryObserver(EffectiveSkinSource source, String name, long observer) {
        this.source = source;
        this.name = name;
        this.observer = observer;
        generation = source.accessories().generation();
        publish(0, CpmAccessoryObservation.Availability.UNKNOWN, false, false);
    }

    public boolean publish(long revision, CpmAccessoryObservation.Availability availability, boolean cape, boolean elytra) {
        return publish(revision, availability, cape, elytra, false, false);
    }

    public boolean publish(long revision, CpmAccessoryObservation.Availability availability, boolean cape, boolean elytra,
            boolean capeContentEmpty, boolean elytraContentEmpty) {
        synchronized (source) {
            if (!source.currentObserver(observer) || revision < modelRevision) return false;
            var current = source.accessories();
            if (modelRevision == revision && current.availability() == availability && current.cape() == cape && current.elytra() == elytra
                    && current.capeContentEmpty() == capeContentEmpty && current.elytraContentEmpty() == elytraContentEmpty) return false;
            modelRevision = revision;
            return CapeProjection.cpmObservation(source, name,
                    new CpmAccessoryObservation(generation = Math.max(generation, current.generation()) + 1, availability, cape, elytra,
                            capeContentEmpty, elytraContentEmpty));
        }
    }
}
