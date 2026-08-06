package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.importing.ExternalImportProbe;
import com.naocraftlab.skins.core.importing.ExternalImportSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


public record ExternalImportModel(
        Category category,
        Map<ExternalImportSource, SourceState> sources,
        Optional<ReviewState> review) {
    public ExternalImportModel {
        Objects.requireNonNull(category, "category");
        sources = Map.copyOf(Objects.requireNonNull(sources, "sources"));
        review = Objects.requireNonNull(review, "review");
        if (!sources.keySet().equals(Set.copyOf(category.sources()))) {
            throw new IllegalArgumentException("external import sources do not match the category");
        }
        sources.values().forEach(value -> Objects.requireNonNull(value, "sources contains null"));
        review.ifPresent(value -> {
            if (!category.sources().contains(value.review().source())) {
                throw new IllegalArgumentException("external import review belongs to another category");
            }
        });
    }

    public static ExternalImportModel open(Category category) {
        EnumMap<ExternalImportSource, SourceState> states =
                new EnumMap<>(ExternalImportSource.class);
        for (ExternalImportSource source : category.sources()) {
            states.put(source, SourceState.probing());
        }
        return new ExternalImportModel(category, states, Optional.empty());
    }

    public ExternalImportModel withAutomaticProbes(
            Map<ExternalImportSource, ExternalImportProbe> availability) {
        EnumMap<ExternalImportSource, SourceState> changed = copySources();
        for (ExternalImportSource source : category.sources()) {
            ExternalImportProbe probe = availability.getOrDefault(
                    source, ExternalImportProbe.UNAVAILABLE);
            changed.put(source, SourceState.automatic(probe));
        }
        return new ExternalImportModel(category, changed, Optional.empty());
    }

    public ExternalImportModel withManualProbe(
            ExternalImportSource source, Path root, boolean available) {
        requireSource(source);
        Objects.requireNonNull(root, "root");
        EnumMap<ExternalImportSource, SourceState> changed = copySources();
        changed.put(source, changed.get(source).manual(root, available));
        return new ExternalImportModel(category, changed, Optional.empty());
    }

    public boolean available(ExternalImportSource source) {
        requireSource(source);
        return sources.get(source).availability().available();
    }

    public Optional<Path> selectedRoot(ExternalImportSource source) {
        requireSource(source);
        return sources.get(source).manualRoot();
    }

    public ExternalImportModel withReview(ClientOperations.ExternalImportReview value) {
        Objects.requireNonNull(value, "value");
        requireSource(value.source());
        return new ExternalImportModel(
                category, sources, Optional.of(ReviewState.open(value)));
    }

    public ExternalImportModel clearReview() {
        return new ExternalImportModel(category, sources, Optional.empty());
    }

    public ExternalImportModel toggleCandidate(String candidateId) {
        return withChangedReview(review.orElseThrow().toggle(candidateId));
    }

    public ExternalImportModel toggleAll() {
        return withChangedReview(review.orElseThrow().toggleAll());
    }

    public ExternalImportModel toggleCollection(boolean duplicates) {
        return withChangedReview(review.orElseThrow().toggleCollection(duplicates));
    }

    public ExternalImportModel withReviewScroll(int scrollOffset) {
        return withChangedReview(review.orElseThrow().withScrollOffset(scrollOffset));
    }

    public Optional<ClientOperations.ExternalImportCandidate> candidate(String candidateId) {
        return review.flatMap(value -> value.review().candidates().stream()
                .filter(candidate -> candidate.id().equals(candidateId))
                .findFirst());
    }

    private ExternalImportModel withChangedReview(ReviewState changed) {
        return new ExternalImportModel(category, sources, Optional.of(changed));
    }

    private EnumMap<ExternalImportSource, SourceState> copySources() {
        EnumMap<ExternalImportSource, SourceState> changed =
                new EnumMap<>(ExternalImportSource.class);
        changed.putAll(sources);
        return changed;
    }

    private void requireSource(ExternalImportSource source) {
        Objects.requireNonNull(source, "source");
        if (!category.sources().contains(source)) {
            throw new IllegalArgumentException("external import source is outside the category");
        }
    }

    public enum Category {
        LAUNCHER(List.of(
                ExternalImportSource.MINECRAFT_LAUNCHER,
                ExternalImportSource.CURSEFORGE_APP,
                ExternalImportSource.MODRINTH_APP,
                ExternalImportSource.PRISM_LAUNCHER)),
        MOD(List.of(ExternalImportSource.SKIN_SHUFFLE));

        private final List<ExternalImportSource> sources;

        Category(List<ExternalImportSource> sources) {
            this.sources = List.copyOf(sources);
        }

        public List<ExternalImportSource> sources() {
            return sources;
        }
    }

    public enum Availability {
        PROBING(false),
        DEPENDENCY_MISSING(false),
        UNAVAILABLE(false),
        AVAILABLE_STANDARD(true),
        AVAILABLE_MANUAL(true);

        private final boolean available;

        Availability(boolean available) {
            this.available = available;
        }

        public boolean available() {
            return available;
        }
    }

    public record SourceState(
            Availability availability,
            Optional<Path> manualRoot,
            int manualFailures) {
        public SourceState {
            Objects.requireNonNull(availability, "availability");
            manualRoot = Objects.requireNonNull(manualRoot, "manualRoot")
                    .map(path -> path.toAbsolutePath().normalize());
            if (manualFailures < 0 || manualFailures > 1024) {
                throw new IllegalArgumentException("manual failure count is out of range");
            }
            if (availability == Availability.AVAILABLE_MANUAL && manualRoot.isEmpty()) {
                throw new IllegalArgumentException("manual availability requires a root");
            }
            if (availability != Availability.AVAILABLE_MANUAL && manualRoot.isPresent()) {
                throw new IllegalArgumentException("only manual availability may retain a root");
            }
        }

        public static SourceState probing() {
            return new SourceState(Availability.PROBING, Optional.empty(), 0);
        }

        public static SourceState automatic(ExternalImportProbe probe) {
            Objects.requireNonNull(probe, "probe");
            Availability availability = switch (probe) {
                case AVAILABLE -> Availability.AVAILABLE_STANDARD;
                case UNAVAILABLE -> Availability.UNAVAILABLE;
                case DEPENDENCY_MISSING -> Availability.DEPENDENCY_MISSING;
            };
            return new SourceState(
                    availability,
                    Optional.empty(),
                    0);
        }

        public SourceState manual(Path root, boolean available) {
            if (available) {
                return new SourceState(
                        Availability.AVAILABLE_MANUAL, Optional.of(root), manualFailures);
            }
            return new SourceState(
                    availability,
                    manualRoot,
                    Math.min(1024, manualFailures + 1));
        }
    }

    public record ReviewState(
            ClientOperations.ExternalImportReview review,
            Set<String> selectedIds,
            Set<Boolean> collapsedCollections,
            int scrollOffset) {
        public ReviewState {
            Objects.requireNonNull(review, "review");
            selectedIds = Set.copyOf(Objects.requireNonNull(selectedIds, "selectedIds"));
            collapsedCollections = Set.copyOf(
                    Objects.requireNonNull(collapsedCollections, "collapsedCollections"));
            if (scrollOffset < 0) {
                throw new IllegalArgumentException("review scroll offset must not be negative");
            }
            Set<String> candidateIds = review.candidates().stream()
                    .map(ClientOperations.ExternalImportCandidate::id)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!candidateIds.containsAll(selectedIds)) {
                throw new IllegalArgumentException("review selects an unknown candidate");
            }
        }

        public static ReviewState open(ClientOperations.ExternalImportReview review) {
            Set<String> selected = review.candidates().stream()
                    .filter(candidate -> !candidate.duplicate())
                    .map(ClientOperations.ExternalImportCandidate::id)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return new ReviewState(review, selected, Set.of(), 0);
        }

        public ReviewState toggle(String candidateId) {
            Objects.requireNonNull(candidateId, "candidateId");
            if (review.candidates().stream().noneMatch(candidate -> candidate.id().equals(candidateId))) {
                throw new IllegalArgumentException("external import candidate is unavailable");
            }
            Set<String> changed = new HashSet<>(selectedIds);
            if (!changed.remove(candidateId)) {
                changed.add(candidateId);
            }
            return new ReviewState(review, changed, collapsedCollections, scrollOffset);
        }

        public ReviewState toggleAll() {
            if (selectedIds.size() == review.candidates().size()) {
                return new ReviewState(review, Set.of(), collapsedCollections, scrollOffset);
            }
            Set<String> all = review.candidates().stream()
                    .map(ClientOperations.ExternalImportCandidate::id)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return new ReviewState(review, all, collapsedCollections, scrollOffset);
        }

        public ReviewState toggleCollection(boolean duplicates) {
            Set<Boolean> changed = new HashSet<>(collapsedCollections);
            if (!changed.remove(duplicates)) {
                changed.add(duplicates);
            }
            return new ReviewState(review, selectedIds, changed, scrollOffset);
        }

        public ReviewState withScrollOffset(int value) {
            return new ReviewState(
                    review, selectedIds, collapsedCollections, Math.max(0, value));
        }

        public List<ClientOperations.ExternalImportCandidate> candidates(boolean duplicates) {
            return review.candidates().stream()
                    .filter(candidate -> candidate.duplicate() == duplicates)
                    .toList();
        }

        public List<ClientOperations.ExternalImportCandidate> selectedCandidates() {
            List<ClientOperations.ExternalImportCandidate> selected = new ArrayList<>();
            for (ClientOperations.ExternalImportCandidate candidate : review.candidates()) {
                if (selectedIds.contains(candidate.id())) {
                    selected.add(candidate);
                }
            }
            return List.copyOf(selected);
        }

        public boolean collectionCollapsed(boolean duplicates) {
            return collapsedCollections.contains(duplicates);
        }

        public boolean allSelected() {
            return selectedIds.size() == review.candidates().size();
        }
    }
}
