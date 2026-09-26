package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.compatibility.SkinFeatureEvidence;
import com.naocraftlab.skins.core.importing.ExternalImportProbe;
import com.naocraftlab.skins.core.importing.ExternalImportSource;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.model.SkinVariant;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface ImportOperations {
    ImportDraft loadPlayerSkin(String playerNameOrUuid) throws Exception;
    ImportDraft loadUrlSkin(String url) throws Exception;
    ExternalImportProbe probeExternalSource(ExternalImportSource source, Optional<Path> selectedRoot) throws Exception;
    ExternalImportReview prepareExternalAppearances(ExternalImportSource source, Optional<Path> selectedRoot) throws Exception;
    ExternalImportResult commitExternalAppearances(List<ExternalImportCandidate> selected, int skipped, int warnings) throws Exception;

    record ExternalImportResult(
            AccountState account,
            int imported,
            int alreadyPresent,
            int skipped,
            int warnings) {
        public ExternalImportResult {
            Objects.requireNonNull(account, "account");
            if (imported < 0 || alreadyPresent < 0 || skipped < 0 || warnings < 0) {
                throw new IllegalArgumentException("external import counters must not be negative");
            }
        }
    }

    record ExternalImportReview(
            ExternalImportSource source,
            List<ExternalImportCandidate> candidates,
            int skipped,
            int warnings) {
        public ExternalImportReview {
            Objects.requireNonNull(source, "source");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("external import review must not be empty");
            }
            if (candidates.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("external import review contains null");
            }
            if (candidates.stream().map(ExternalImportCandidate::id).distinct().count()
                    != candidates.size()) {
                throw new IllegalArgumentException("external import candidate ids must be unique");
            }
            if (skipped < 0 || warnings < 0) {
                throw new IllegalArgumentException("external import counters must not be negative");
            }
        }
    }

    record AccountRevision(java.util.UUID accountId, java.time.Instant revision) {
        public AccountRevision { Objects.requireNonNull(accountId); Objects.requireNonNull(revision); }
    }

    record ExternalImportCandidate(
            String id,
            String displayName,
            SkinVariant variant,
            PersonalSkinSource source,
            byte[] normalizedPng,
            String sha256,
            String capeId,
            int sourceOrder,
            boolean duplicate,
            SkinFeatureEvidence featureEvidence,
            Optional<AccountRevision> accountRevision) {
        public ExternalImportCandidate(String id, String displayName, SkinVariant variant, PersonalSkinSource source,
                byte[] normalizedPng, String sha256, String capeId, int sourceOrder, boolean duplicate,
                SkinFeatureEvidence featureEvidence) {
            this(id, displayName, variant, source, normalizedPng, sha256, capeId, sourceOrder, duplicate,
                    featureEvidence, Optional.empty());
        }
        public ExternalImportCandidate {
            accountRevision = Objects.requireNonNull(accountRevision, "accountRevision");
            id = Objects.requireNonNull(id, "id");
            displayName = Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(variant, "variant");
            Objects.requireNonNull(source, "source");
            normalizedPng = Objects.requireNonNull(normalizedPng, "normalizedPng").clone();
            sha256 = Objects.requireNonNull(sha256, "sha256");
            featureEvidence = Objects.requireNonNull(featureEvidence, "featureEvidence");
            if (!id.matches("[a-z0-9][a-z0-9_-]{0,127}")) {
                throw new IllegalArgumentException("external import candidate id is invalid");
            }
            if (displayName.isBlank() || displayName.length() > 128) {
                throw new IllegalArgumentException("external import display name is invalid");
            }
            if (normalizedPng.length == 0) {
                throw new IllegalArgumentException("external import PNG must not be empty");
            }
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("external import SHA-256 is invalid");
            }
            if (capeId != null && (capeId.isBlank() || capeId.length() > 256)) {
                throw new IllegalArgumentException("external import cape id is invalid");
            }
            if (sourceOrder < 0) {
                throw new IllegalArgumentException("external import source order must not be negative");
            }
        }

        public ExternalImportCandidate(
                String id,
                String displayName,
                SkinVariant variant,
                PersonalSkinSource source,
                byte[] normalizedPng,
                String sha256,
                String capeId,
                int sourceOrder,
                boolean duplicate) {
            this(
                    id,
                    displayName,
                    variant,
                    source,
                    normalizedPng,
                    sha256,
                    capeId,
                    sourceOrder,
                    duplicate,
                    SkinFeatureEvidence.ORDINARY);
        }

        @Override
        public byte[] normalizedPng() {
            return normalizedPng.clone();
        }
    }

    record ImportDraft(String name, SkinVariant variant, byte[] pngBytes, PersonalSkinSource source) {
        public ImportDraft {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(variant, "variant");
            pngBytes = Objects.requireNonNull(pngBytes, "pngBytes").clone();
            Objects.requireNonNull(source, "source");
        }

        @Override
        public byte[] pngBytes() {
            return pngBytes.clone();
        }
    }
}
