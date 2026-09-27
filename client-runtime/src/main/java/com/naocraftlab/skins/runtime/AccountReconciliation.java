package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Trigger;
import java.util.Optional;
import static com.naocraftlab.skins.runtime.AccountReconciliationPort.*;

public interface AccountReconciliation extends AutoCloseable {
    void request(ReconciliationKey key, Trigger trigger);
    boolean busy();
    void close();

    record Request(ReconciliationKey key, Trigger trigger) {}

    interface Completion {
        void accept(Request request, Optional<ReconciliationResult> result,
                Optional<DurableAppearance> durableAfterFailure, Throwable failure);
    }
}
