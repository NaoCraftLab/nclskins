package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.config.ClientConfiguration;
import com.naocraftlab.skins.diagnostics.DiagnosticSink;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;


public final class ClientApplicationHost<C> implements AutoCloseable {
    private final ClientRuntime runtime;
    private final ClientProcessHost<C> process;

    public ClientApplicationHost(
            ClientCapabilitySet capabilities,
            TextResolver textResolver,
            Path dataRoot,
            DiagnosticSink diagnostics,
            Runnable closeNativeResources) {
        this(
                capabilities,
                textResolver,
                dataRoot,
                ClientConfiguration::defaults,
                diagnostics,
                closeNativeResources);
    }

    public ClientApplicationHost(
            ClientCapabilitySet capabilities,
            TextResolver textResolver,
            Path dataRoot,
            Supplier<ClientConfiguration> configurationSource,
            DiagnosticSink diagnostics,
            Runnable closeNativeResources) {
        Objects.requireNonNull(capabilities, "capabilities");
        runtime = capabilities.createRuntime(
                textResolver,
                Objects.requireNonNull(dataRoot, "dataRoot"),
                Objects.requireNonNull(configurationSource, "configurationSource"),
                Objects.requireNonNull(diagnostics, "diagnostics"));
        process = new ClientProcessHost<>(runtime, closeNativeResources);
    }

    public ClientRuntime runtime() {
        return runtime;
    }

    public void verifyStorageAccess() {
        ensureOpen();
        runtime.verifyStorageAccess();
    }

    public void warmSession() {
        if (!process.closed()) {
            process.warmSession();
        }
    }

    public void tick(C connection, boolean playerReady) {
        if (!process.closed()) {
            process.tick(connection, playerReady);
        }
    }

    public boolean closed() {
        return process.closed();
    }

    @Override
    public void close() {
        process.close();
    }

    private void ensureOpen() {
        if (process.closed()) {
            throw new IllegalStateException("Client application host is closed");
        }
    }
}
