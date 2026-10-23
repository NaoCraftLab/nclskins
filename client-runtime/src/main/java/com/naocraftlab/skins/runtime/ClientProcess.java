package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.runtime.AppearanceRefresh.Result;
import java.util.concurrent.CompletableFuture;

public interface ClientProcess<C> extends AutoCloseable {
    void warmSession();
    void tick(C connection, Object worldIdentity, boolean playerReady);
    boolean closed();
    void close();

    public interface Process extends AutoCloseable {
        void warmSession();

        void tick();

        void worldChanged();

        CompletableFuture<Result> afterReconnect();

        @Override
        void close();
    }
}
