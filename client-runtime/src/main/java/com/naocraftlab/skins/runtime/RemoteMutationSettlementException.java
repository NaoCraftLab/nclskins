package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.service.RemoteAppearanceImpact;
import java.util.Objects;


final class RemoteMutationSettlementException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private static final String SAFE_MESSAGE =
            "Remote appearance mutation settled before local reconciliation failed.";

    private final RemoteAppearanceImpact remoteAppearanceImpact;

    RemoteMutationSettlementException(RemoteAppearanceImpact remoteAppearanceImpact) {
        super(SAFE_MESSAGE);
        this.remoteAppearanceImpact =
                Objects.requireNonNull(remoteAppearanceImpact, "remoteAppearanceImpact");
    }

    RemoteAppearanceImpact remoteAppearanceImpact() {
        return remoteAppearanceImpact;
    }
}
