package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.model.AccountState;
import com.naocraftlab.skins.core.model.SkinVariant;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

record PersonalCatalogView(UUID accountId, Instant revision, AccountState account,
        CatalogEquivalenceIndex<Candidate> equivalenceIndex) {
    PersonalCatalogView {
        Objects.requireNonNull(accountId); Objects.requireNonNull(revision);
        Objects.requireNonNull(account); Objects.requireNonNull(equivalenceIndex);
    }
    Optional<Candidate> first(String visualHash) {
        return equivalenceIndex.first(new PreparedCatalogSnapshot.VisualIdentity(visualHash));
    }
    record Candidate(UUID assetId, String sha256, SkinVariant model, boolean visible,
            PreparedCatalogSnapshot.VisualIdentity visual) {}
}
