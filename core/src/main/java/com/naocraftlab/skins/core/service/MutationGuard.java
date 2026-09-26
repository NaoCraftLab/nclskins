package com.naocraftlab.skins.core.service;

import java.io.IOException;
import java.util.UUID;

public interface MutationGuard {
    Lease acquireRemoteMutationLock(UUID accountId) throws IOException;
    interface Lease extends AutoCloseable { void close() throws IOException; }
}
