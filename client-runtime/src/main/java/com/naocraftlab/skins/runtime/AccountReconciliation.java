package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.reconciliation.ReconciliationPolicy.Trigger;
import java.util.Optional;
import static com.naocraftlab.skins.runtime.AccountReconciliationPort.*;

public interface AccountReconciliation extends AutoCloseable {
    void request(ReconciliationKey key, Trigger trigger);
    void request(Request request);
    boolean busy();
    void close();

    record Request(ReconciliationKey key, Trigger trigger,
            java.util.function.BooleanSupplier stillCurrent,
            java.util.function.BooleanSupplier publicationCurrent, boolean existingIntentOnly) {
        public Request(ReconciliationKey key, Trigger trigger) { this(key, trigger, () -> true, () -> true, false); }
        public Request(ReconciliationKey key, Trigger trigger, java.util.function.BooleanSupplier stillCurrent,
                java.util.function.BooleanSupplier publicationCurrent) { this(key, trigger, stillCurrent, publicationCurrent, true); }
    }

    interface Completion {
        void accept(Request request, Optional<ReconciliationResult> result,
                Optional<DurableAppearance> durableAfterFailure, Throwable failure);
    }
}
