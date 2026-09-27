package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.service.LibraryService;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class LibraryExternalImportAdapter implements ExternalImportCommit {
    private final LibraryService library;
    private final CatalogAccountAccess accounts;
    public LibraryExternalImportAdapter(LibraryService library, CatalogAccountAccess accounts) {
        this.library = Objects.requireNonNull(library);
        this.accounts = Objects.requireNonNull(accounts);
    }
    @Override public BatchResult commit(UUID accountId, List<ImportOperations.ExternalImportCandidate> candidates)
            throws Exception {
        accounts.requireCurrent(accountId);
        var imports = candidates.stream().map(candidate -> new LibraryService.PersonalSkinPresetImport(
                candidate.displayName(), candidate.displayName(), candidate.variant(), candidate.source(),
                candidate.normalizedPng(), candidate.capeId())).toList();
        var imported = library.importPersonalSkinPresets(accountId, imports, current -> {
            accounts.requireCurrent(accountId);
            for (var candidate : candidates) {
                if (candidate.accountRevision().isPresent()) {
                    var expected = candidate.accountRevision().orElseThrow();
                    if (!expected.accountId().equals(current.accountId()) || !expected.revision().equals(current.updatedAt())) {
                        throw new java.io.IOException("Import review belongs to a stale account revision");
                    }
                }
            }
        });
        return new BatchResult(imported.state(), imported.imported(), imported.alreadyPresent());
    }
}
