package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.importing.ExternalAppearanceRecord;
import com.naocraftlab.skins.core.importing.ExternalImportBatch;
import com.naocraftlab.skins.core.importing.ExternalImportContext;
import com.naocraftlab.skins.core.importing.ExternalImportProbe;
import com.naocraftlab.skins.core.importing.ExternalImportSource;
import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.OwnedCapeInventory;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidator;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ExternalAppearanceImportService {
    private final ExternalImportSourceAccess sources;
    private final PreparedCatalogService catalogs;
    private final ExternalImportCommit commits;
    private final PngValidator pngValidator = new PngValidator();

    public ExternalAppearanceImportService(ExternalImportSourceAccess sources, PreparedCatalogService catalogs,
            ExternalImportCommit commits) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.commits = Objects.requireNonNull(commits, "commits");
    }

    ExternalImportProbe probe(ExternalImportSource source, Optional<Path> selectedRoot, ExternalImportContext context) {
        return sources.probe(source, selectedRoot, context);
    }

    ImportOperations.ExternalImportReview prepareAppearances(
            UUID accountId,
            ExternalImportSource source,
            Optional<Path> selectedRoot,
            ExternalImportContext context,
            OwnedCapeInventory ownedCapes) throws Exception {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(selectedRoot, "selectedRoot");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(ownedCapes, "ownedCapes");
        ExternalImportBatch batch = sources.discover(source, selectedRoot, context);
        PersonalCatalogView existingSkins = catalogs.personalView(accountId);
        List<ImportOperations.ExternalImportCandidate> resolved = new ArrayList<>();
        int skipped = 0;
        int warnings = batch.warnings().size();
        for (ExternalAppearanceRecord record : batch.records()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Import preparation cancelled");
            catalogs.requirePersonalView(existingSkins);
            try {
                ExternalImportSourceAccess.Resolution resolution = sources.resolve(record);
                String name = UntrustedDisplayName.sanitize(record.displayName(), "Imported look");
                String capeId = record.externalCapeId()
                        .filter(id -> ownedCapes.find(id).isPresent())
                        .orElse(null);
                if (record.externalCapeId().isPresent() && capeId == null) {
                    warnings++;
                }
                SkinVariant variant = record.declaredVariant().orElse(resolution.variant());
                byte[] resolvedPng = resolution.pngBytes();
                PersonalCatalogView.Candidate existing = existingSkins.first(pngValidator.renderSha256(resolvedPng)).orElse(null);
                byte[] candidatePng = existing == null ? resolvedPng : catalogs.loadPersonalCandidate(existingSkins, existing);
                String sha256 = existing == null ? sha256(candidatePng) : existing.sha256();
                resolved.add(new ImportOperations.ExternalImportCandidate(
                        "candidate-" + resolved.size(),
                        name,
                        variant,
                        resolution.source(),
                        candidatePng,
                        sha256,
                        capeId,
                        record.sourceOrder(),
                        existing != null && existing.visible(),
                        pngValidator.projectImport(candidatePng).featureEvidence(),
                        Optional.of(new ImportOperations.AccountRevision(existingSkins.accountId(), existingSkins.revision()))));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (Exception rejectedRecord) {
                skipped++;
            }
        }
        catalogs.requirePersonalView(existingSkins);
        if (resolved.isEmpty()) {
            throw new ExternalImportException(
                    ExternalImportException.Code.NO_VALID_APPEARANCES,
                    "The source contains no importable appearances");
        }
        return new ImportOperations.ExternalImportReview(
                source, resolved, skipped, warnings);
    }

    Result commitAppearances(
            UUID accountId,
            List<ImportOperations.ExternalImportCandidate> selected,
            int skipped,
            int warnings) throws Exception {
        Objects.requireNonNull(accountId, "accountId");
        List<ImportOperations.ExternalImportCandidate> candidates = List.copyOf(
                Objects.requireNonNull(selected, "selected"));
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("At least one external appearance must be selected");
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Import commit cancelled");
        for (var candidate : candidates) catalogs.requireImportContext(accountId, candidate.accountRevision());
        ExternalImportCommit.BatchResult imported = commits.commit(accountId, candidates);
        return new Result(
                imported.state(), imported.imported(), imported.alreadyPresent(), skipped, warnings);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    record Result(
            AccountState state,
            int imported,
            int alreadyPresent,
            int skipped,
            int warnings) {
        Result {
            Objects.requireNonNull(state, "state");
            if (imported < 0 || alreadyPresent < 0 || skipped < 0 || warnings < 0) {
                throw new IllegalArgumentException("external import counters must not be negative");
            }
        }
    }

}
