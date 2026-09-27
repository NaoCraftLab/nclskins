package com.naocraftlab.skins.server.runtime;

import com.naocraftlab.skins.server.ServerPlayerIdentity;
import com.naocraftlab.skins.server.SignedTexturesProperty;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public interface SessionProfileSource {
    CompletionStage<Result> fetchAsync(ServerPlayerIdentity identity);

    public static final class FetchedProfile {
        private final ServerPlayerIdentity identity;
        private final Optional<SignedTexturesProperty> textures;

        public FetchedProfile(
                ServerPlayerIdentity identity,
                Optional<SignedTexturesProperty> textures) {
            this.identity = Objects.requireNonNull(identity, "identity");
            this.textures = Objects.requireNonNull(textures, "textures");
        }

        public ServerPlayerIdentity identity() {
            return identity;
        }

        public Optional<SignedTexturesProperty> textures() {
            return textures;
        }

        @Override
        public String toString() {
            return "FetchedProfile[redacted]";
        }
    }

    public static final class Result {
        private final Status status;
        private final FetchedProfile profile;
        private final Duration retryAfter;

        private Result(Status status, FetchedProfile profile, Duration retryAfter) {
            this.status = Objects.requireNonNull(status, "status");
            this.profile = profile;
            this.retryAfter = retryAfter;
        }

        public static Result resolved(FetchedProfile profile) {
            return new Result(Status.RESOLVED, Objects.requireNonNull(profile, "profile"), null);
        }

        public static Result transientFailure() {
            return new Result(Status.TRANSIENT_FAILURE, null, null);
        }

        public static Result throttled(Duration retryAfter) {
            return new Result(
                    Status.THROTTLED,
                    null,
                    Objects.requireNonNull(retryAfter, "retryAfter"));
        }

        public static Result rejected() {
            return new Result(Status.REJECTED, null, null);
        }

        public Status status() {
            return status;
        }

        public Optional<FetchedProfile> profile() {
            return Optional.ofNullable(profile);
        }

        public Optional<Duration> retryAfter() {
            return Optional.ofNullable(retryAfter);
        }

        @Override
        public String toString() {
            return "OfficialSessionProfileResult[status=" + status + ']';
        }

        public enum Status {
            RESOLVED,
            TRANSIENT_FAILURE,
            THROTTLED,
            REJECTED
        }
    }

}
