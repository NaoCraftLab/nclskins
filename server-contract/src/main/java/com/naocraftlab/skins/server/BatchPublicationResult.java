package com.naocraftlab.skins.server;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;


public final class BatchPublicationResult {
    private final Map<ConnectionKey, PublicationOutcome> outcomes;
    private final PublicationMetrics metrics;

    private BatchPublicationResult(
            Map<ConnectionKey, PublicationOutcome> outcomes,
            PublicationMetrics metrics) {
        this.outcomes = Collections.unmodifiableMap(new LinkedHashMap<>(outcomes));
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public static BatchPublicationResult of(Map<ConnectionKey, PublicationOutcome> outcomes) {
        return of(outcomes, PublicationMetrics.ZERO);
    }

    public static BatchPublicationResult of(
            Map<ConnectionKey, PublicationOutcome> outcomes,
            PublicationMetrics metrics) {
        Objects.requireNonNull(outcomes, "outcomes");
        Map<ConnectionKey, PublicationOutcome> copy = new LinkedHashMap<>();
        outcomes.forEach((key, outcome) -> copy.put(
                Objects.requireNonNull(key, "connection key"),
                Objects.requireNonNull(outcome, "publication outcome")));
        return new BatchPublicationResult(copy, metrics);
    }

    public static BatchPublicationResult all(
            List<PublicationRequest> requests,
            PublicationOutcome outcome) {
        Objects.requireNonNull(requests, "requests");
        Objects.requireNonNull(outcome, "outcome");
        Map<ConnectionKey, PublicationOutcome> results = new LinkedHashMap<>();
        for (PublicationRequest request : requests) {
            PublicationRequest item = Objects.requireNonNull(request, "publication request");
            results.put(item.connection(), outcome);
        }
        return of(results);
    }

    public Optional<PublicationOutcome> outcome(ConnectionKey connection) {
        return Optional.ofNullable(outcomes.get(Objects.requireNonNull(connection, "connection")));
    }

    public int size() {
        return outcomes.size();
    }

    public PublicationMetrics metrics() {
        return metrics;
    }

    @Override
    public String toString() {
        return "BatchPublicationResult[count=" + outcomes.size() + ", metrics=" + metrics + ']';
    }
}
