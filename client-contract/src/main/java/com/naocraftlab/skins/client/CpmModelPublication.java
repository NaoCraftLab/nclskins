package com.naocraftlab.skins.client;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

@FunctionalInterface
public interface CpmModelPublication {
    Result publish(Request request, BooleanSupplier current);

    interface Control extends BooleanSupplier {
        long remainingNanos();
        boolean beginSend();
    }

    final class Request {
        private final byte[] wire;
        private final long revision;
        private final String name;
        public Request(byte[] wire, long revision, String name) {
            Objects.requireNonNull(wire, "wire");
            if (wire.length == 0 || revision < 0)
                throw new IllegalArgumentException("Invalid publication bounds");
            this.wire = wire.clone();
            this.revision = revision;
            this.name = CpmPublicationName.fromBasename(name);
        }
        public byte[] wire() { return wire.clone(); }
        public long revision() { return revision; }
        public String name() { return name; }
        @Override public String toString() { return "CpmModelPublication.Request[private]"; }
    }

    enum Status { NOT_SENT, CONFIRMED, UNCERTAIN }
    enum Reason { NONE, NO_CONSENT, MISSING_SESSION, PROOF_REJECTED, STALE, EXPIRED, NETWORK, PROTOCOL, COUNT_QUOTA, SIZE_QUOTA }
    record Result(Status status, Reason reason, Optional<String> pasteId) {
        public Result {
            Objects.requireNonNull(status); Objects.requireNonNull(reason); Objects.requireNonNull(pasteId);
            if (status == Status.CONFIRMED != pasteId.isPresent()) throw new IllegalArgumentException("Invalid publication result");
        }
        public static Result stopped(Reason reason) { return new Result(Status.NOT_SENT, reason, Optional.empty()); }
    }
}
