package com.naocraftlab.skins.server.runtime;

import com.naocraftlab.skins.server.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class SessionProfileSourceTest {
    private final ConnectionSnapshot connection = new ConnectionSnapshot(
            new ConnectionKey(new UUID(0, 1), 1), "Fixture", IdentityAssurance.ONLINE);

    @Test
    void consumerMapsAbsenceFailureAndThrottleWithoutTransport() {
        var inputs = new SessionProfileSource.Result[] {
                SessionProfileSource.Result.resolved(new SessionProfileSource.FetchedProfile(connection.identity(), Optional.empty())),
                SessionProfileSource.Result.rejected(),
                SessionProfileSource.Result.transientFailure(),
                SessionProfileSource.Result.throttled(Duration.ofSeconds(3))
        };
        for (var input : inputs) {
            var service = new OfficialProfileResolutionService(
                    identity -> CompletableFuture.completedFuture(input),
                    (property, identity) -> { throw new AssertionError("Unexpected signature operation"); });
            var result = service.resolve(connection).toCompletableFuture().join();
            assertEquals(input.status().name(), result.status().name());
            assertEquals(input.retryAfter(), result.retryAfter());
        }
    }

    @Test
    void cancellationPropagatesToConsumerOwnedSource() {
        var upstream = new CompletableFuture<SessionProfileSource.Result>();
        var service = new OfficialProfileResolutionService(identity -> upstream,
                (property, identity) -> Optional.empty());
        service.resolve(connection).toCompletableFuture().cancel(true);
        assertTrue(upstream.isCancelled());
    }
}
