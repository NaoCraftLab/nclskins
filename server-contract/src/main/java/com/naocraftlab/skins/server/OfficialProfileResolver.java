package com.naocraftlab.skins.server;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;


@FunctionalInterface
public interface OfficialProfileResolver {
    CompletionStage<Resolution> resolve(ConnectionSnapshot expectedConnection);

    static CompletionStage<Resolution> completed(Resolution resolution) {
        return CompletableFuture.completedFuture(Objects.requireNonNull(resolution, "resolution"));
    }


    final class Resolution {
        private final Status status;
        private final VerifiedOfficialProfile profile;
        private final Duration retryAfter;

        private Resolution(
                Status status,
                VerifiedOfficialProfile profile,
                Duration retryAfter) {
            this.status = Objects.requireNonNull(status, "status");
            this.profile = profile;
            this.retryAfter = retryAfter;
        }

        public static Resolution resolved(VerifiedOfficialProfile profile) {
            return new Resolution(
                    Status.RESOLVED,
                    Objects.requireNonNull(profile, "profile"),
                    null);
        }

        public static Resolution transientFailure() {
            return new Resolution(Status.TRANSIENT_FAILURE, null, null);
        }

        public static Resolution throttled(Duration retryAfter) {
            Objects.requireNonNull(retryAfter, "retryAfter");
            if (retryAfter.isNegative()) {
                throw new IllegalArgumentException("Retry delay must not be negative");
            }
            return new Resolution(Status.THROTTLED, null, retryAfter);
        }

        public static Resolution rejected() {
            return new Resolution(Status.REJECTED, null, null);
        }

        public Status status() {
            return status;
        }

        public Optional<VerifiedOfficialProfile> profile() {
            return Optional.ofNullable(profile);
        }

        public Optional<Duration> retryAfter() {
            return Optional.ofNullable(retryAfter);
        }


        @Override
        public String toString() {
            return "OfficialProfileResolution[status=" + status + ']';
        }

        public enum Status {
            RESOLVED,
            TRANSIENT_FAILURE,
            THROTTLED,
            REJECTED
        }
    }
}
