package com.naocraftlab.skins.runtime;

import java.util.Objects;


public interface ClientCapabilityProvider {
    Provision provision();

    record Provision(
            ClientCapabilitySet capabilities,
            Runnable maintainNativeResources,
            Runnable closeNativeResources) {
        public Provision {
            Objects.requireNonNull(capabilities, "capabilities");
            Objects.requireNonNull(maintainNativeResources, "maintainNativeResources");
            Objects.requireNonNull(closeNativeResources, "closeNativeResources");
        }

        public void maintain() {
            maintainNativeResources.run();
        }

        public void closeNative() {
            closeNativeResources.run();
        }
    }
}
