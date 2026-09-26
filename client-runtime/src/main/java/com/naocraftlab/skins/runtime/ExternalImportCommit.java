package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.model.AccountState;
import java.util.List;
import java.util.UUID;

interface ExternalImportCommit {
    BatchResult commit(UUID accountId, List<ImportOperations.ExternalImportCandidate> candidates) throws Exception;
    record BatchResult(AccountState state, int imported, int alreadyPresent) {}
}
