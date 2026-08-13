package com.naocraftlab.skins.server.plugin.common;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;


public final class RelayRevisionFence {
    private byte[] nonce;
    private long revision;
    private Phase phase = Phase.NONE;

    public synchronized void bind(byte[] value) {
        Objects.requireNonNull(value, "value");
        if (value.length != ProxyRefreshProtocol.NONCE_BYTES) {
            throw new IllegalArgumentException("Relay nonce must contain exactly 16 bytes");
        }
        nonce = value.clone();
        revision = 0;
        phase = Phase.NONE;
    }

    public synchronized boolean acceptDirty(byte[] candidateNonce, long candidateRevision) {
        if (!matches(candidateNonce) || candidateRevision <= revision) {
            return false;
        }
        revision = candidateRevision;
        phase = Phase.DIRTY;
        return true;
    }

    public synchronized boolean acceptState(byte[] candidateNonce, long candidateRevision) {
        if (!matches(candidateNonce) || candidateRevision != revision || phase != Phase.DIRTY) {
            return false;
        }
        phase = Phase.STATE;
        return true;
    }

    public synchronized boolean isDirty() {
        return phase == Phase.DIRTY;
    }

    public synchronized long revision() {
        return revision;
    }

    public synchronized void clear() {
        if (nonce != null) {
            Arrays.fill(nonce, (byte) 0);
        }
        nonce = null;
        revision = 0;
        phase = Phase.NONE;
    }

    private boolean matches(byte[] candidateNonce) {
        return nonce != null && candidateNonce != null &&
                MessageDigest.isEqual(nonce, candidateNonce);
    }

    private enum Phase {
        NONE,
        DIRTY,
        STATE
    }
}
