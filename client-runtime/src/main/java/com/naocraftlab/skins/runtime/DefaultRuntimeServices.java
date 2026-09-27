package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.ServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.diagnostics.DiagnosticSink;
import com.naocraftlab.skins.diagnostics.DiagnosticEvent;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

public final class DefaultRuntimeServices implements RuntimeServices {
    public PreviewAssets previewAssets(ClientExecutor client, Executor worker, DiagnosticSink diagnostics) {
        return new PreviewAssetLoader(client, worker, diagnostics);
    }
    public AccountReconciliation reconciliation(AccountReconciliationPort operations, Executor worker,
            Consumer<Runnable> client, AccountReconciliation.Completion completion, Runnable idle,
            BiConsumer<DiagnosticEvent, Throwable> diagnose) {
        return new AccountReconciliationCoordinator(operations, worker, client, completion, idle, diagnose);
    }
    public ServerAppearanceReadiness readiness(ServerAppearanceRefreshNotifier notifier) {
        return new ServerAppearanceReadinessCoordinator(notifier);
    }
    public <C> ClientProcess<C> process(ClientProcess.Process process) {
        return new ClientProcessHost<>(process);
    }
}
