package com.naocraftlab.skins.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class SourcePreparationState<K, C, O> {
    record Ticket<K, C, O>(K source, C context, O observation, long revision, boolean remoteRead) {}

    private final Map<K, Entry<K, C, O>> entries = new HashMap<>();
    private final Set<K> sources;
    private long revision;

    SourcePreparationState(Set<K> sources) { this.sources = Set.copyOf(sources); }

    synchronized void offer(K source, C context, O observation, boolean enabled, boolean remoteRead) {
        if (!sources.contains(source)) throw new IllegalArgumentException("Missing candidate binding");
        Entry<K, C, O> entry = entries.get(source);
        if (!enabled || observation == null) { entries.remove(source); return; }
        boolean same = entry != null && Objects.equals(entry.ticket.context(), context)
                && Objects.equals(entry.ticket.observation(), observation);
        if (same && (entry.prepared || entry.ticket.remoteRead() || !remoteRead)
                && (entry.pending || entry.issued || entry.prepared)) return;
        entries.put(source, new Entry<>(new Ticket<>(source, context, observation, ++revision, remoteRead)));
    }

    synchronized List<Ticket<K, C, O>> pending() {
        return entries.values().stream().filter(entry -> entry.pending).map(entry -> entry.ticket).toList();
    }

    synchronized List<Ticket<K, C, O>> issue() {
        List<Ticket<K, C, O>> batch = new ArrayList<>();
        for (var entry : entries.values()) if (entry.pending) {
            entry.pending = false;
            entry.issued = true;
            batch.add(entry.ticket);
        }
        return List.copyOf(batch);
    }

    synchronized boolean current(Ticket<K, C, O> ticket) {
        Entry<K, C, O> entry = entries.get(ticket.source());
        return entry != null && entry.ticket == ticket;
    }

    synchronized void complete(Ticket<K, C, O> ticket, boolean prepared) {
        Entry<K, C, O> entry = entries.get(ticket.source());
        if (entry == null || entry.ticket != ticket) return;
        entry.pending = false;
        entry.issued = false;
        entry.prepared = prepared;
    }

    synchronized void rejectPending(Ticket<K, C, O> ticket) {
        Entry<K, C, O> entry = entries.get(ticket.source());
        if (entry != null && entry.ticket == ticket && entry.pending) entry.pending = false;
    }

    synchronized void invalidate() { entries.clear(); }

    private static final class Entry<K, C, O> {
        final Ticket<K, C, O> ticket;
        boolean pending = true;
        boolean issued;
        boolean prepared;
        Entry(Ticket<K, C, O> ticket) { this.ticket = ticket; }
    }
}
