package com.naocraftlab.skins.runtime;

import java.util.Objects;


public interface ClientCapabilityProvider {
    Provision provision();

    record Provision(
            ClientCapabilitySet capabilities,
            NativeResourceMaintenance nativeResourceMaintenance,
            Runnable closeNativeResources) {
        public Provision {
            Objects.requireNonNull(capabilities, "capabilities");
            Objects.requireNonNull(nativeResourceMaintenance, "nativeResourceMaintenance");
            Objects.requireNonNull(closeNativeResources, "closeNativeResources");
        }

        public void maintain(boolean playerReady) {
            nativeResourceMaintenance.tick(playerReady);
        }

        public void markNativeResourcesDirty() {
            nativeResourceMaintenance.markDirty();
        }

        public void closeNative() {
            nativeResourceMaintenance.close();
            closeNativeResources.run();
        }
    }
}
