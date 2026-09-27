package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.ClientExecutor;
import com.naocraftlab.skins.client.ServerAppearanceRefreshNotifier;
import com.naocraftlab.skins.diagnostics.DiagnosticSink;
import com.naocraftlab.skins.diagnostics.DiagnosticEvent;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

public interface RuntimeServices {
    PreviewAssets previewAssets(ClientExecutor client, Executor worker, DiagnosticSink diagnostics);
    AccountReconciliation reconciliation(AccountReconciliationPort operations, Executor worker,
            Consumer<Runnable> client, AccountReconciliation.Completion completion, Runnable idle,
            BiConsumer<DiagnosticEvent, Throwable> diagnose);
    ServerAppearanceReadiness readiness(ServerAppearanceRefreshNotifier notifier);
    <C> ClientProcess<C> process(ClientProcess.Process process);
}
