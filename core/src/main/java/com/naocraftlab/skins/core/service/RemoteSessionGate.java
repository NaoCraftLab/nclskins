package com.naocraftlab.skins.core.service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


public final class RemoteSessionGate {
    private final Set<UUID> expiredAccounts = ConcurrentHashMap.newKeySet();

    public boolean remoteControlsBlocked(UUID accountId) {
        return expiredAccounts.contains(accountId);
    }

    void block(UUID accountId) {
        expiredAccounts.add(accountId);
    }

    void clearAfterSuccessfulManualRetry(UUID accountId) {
        expiredAccounts.remove(accountId);
    }
}
