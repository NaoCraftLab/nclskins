package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.CurrentPlayerAppearanceSource;
import com.naocraftlab.skins.client.OuterLayerVisibility;
import com.naocraftlab.skins.client.OuterLayerVisibilityController;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class NativeMenuRuntimeFixture {
    public static ClientRuntime create(ClientOperations operations, PreviewModelFitStore store,
            CurrentPlayerAppearanceSource source, OuterLayerVisibility[] mask) {
        return new ClientRuntime(new DefaultRuntimeServices(store), operations, new ClientExecutor() {
            public boolean isClientThread() {
                return true;
            }
            public void execute(Runnable action) {
                action.run();
            }
        }, () -> CompletableFuture.completedFuture(Optional.empty()), action -> {
            throw new AssertionError("menu worker demand");
        }, UiMessage::key, Optional.of(source), Optional.empty(), Optional.of(new OuterLayerVisibilityController() {
            public OuterLayerVisibility current() {
                return mask[0];
            }
            public void applyDurable(OuterLayerVisibility value) {
                throw new AssertionError("menu preference write");
            }
        }), Optional.empty(), com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
    }
}
