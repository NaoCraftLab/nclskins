package com.naocraftlab.skins.runtime;

import com.google.gson.JsonObject;
import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.GameSessionTokenUnavailableException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

final class OptiFineAccountLink {
    enum Outcome { READY, AUTH_REQUIRED, FAILED, CANCELLED, EXPIRED }

    record Result(Outcome outcome) {
        Result { Objects.requireNonNull(outcome, "outcome"); }
    }

    @FunctionalInterface
    interface JoinTransport {
        void join(UUID profileId, String accessToken, String serverId) throws IOException, InterruptedException;
    }

    private static final URI JOIN_ENDPOINT = URI.create("https://sessionserver.mojang.com/session/minecraft/join");
    private static final Duration LIFETIME = Duration.ofSeconds(120);
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,64}");

    private final GameSessionTokenSource sessions;
    private final Executor worker;
    private final JoinTransport joinTransport;
    private final SecureRandom random;
    private final Clock clock;
    private long generation;
    private UUID accountId;
    private Instant deadline;
    private boolean preparing;
    private URI ready;
    private String readyName;

    OptiFineAccountLink(GameSessionTokenSource sessions, Executor worker) {
        this(sessions, worker, httpTransport(), new SecureRandom(), Clock.systemUTC());
    }

    OptiFineAccountLink(GameSessionTokenSource sessions, Executor worker, JoinTransport joinTransport,
            SecureRandom random, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.joinTransport = Objects.requireNonNull(joinTransport, "joinTransport");
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    synchronized boolean preparing() {
        return preparing;
    }

    synchronized boolean ready() {
        return ready != null && !expired();
    }

    synchronized boolean expired() {
        return deadline != null && !clock.instant().isBefore(deadline);
    }

    synchronized URI readyUri(UUID expectedAccount) {
        if (ready == null || expired() || !Objects.equals(accountId, expectedAccount)) return null;
        try {
            GameSessionTokenSource.SessionIdentity current = sessions.currentSession();
            return current != null && expectedAccount.equals(current.profileId())
                    && Objects.equals(readyName, current.profileName()) ? ready : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    synchronized void cancel() {
        generation++;
        accountId = null;
        deadline = null;
        preparing = false;
        ready = null;
        readyName = null;
    }

    synchronized CompletableFuture<Result> begin(UUID account) {
        Objects.requireNonNull(account, "account");
        if (preparing) return CompletableFuture.completedFuture(new Result(Outcome.CANCELLED));
        cancel();
        if (!currentAccount(account)) {
            return CompletableFuture.completedFuture(new Result(Outcome.AUTH_REQUIRED));
        }
        long ticket = generation;
        accountId = account;
        deadline = clock.instant().plus(LIFETIME);
        preparing = true;
        return CompletableFuture.supplyAsync(() -> prepare(ticket, account), worker);
    }

    private Result prepare(long ticket, UUID account) {
        try {
            requireLive(ticket, account);
            GameSessionTokenSource.SessionIdentity before = sessions.currentSession();
            if (before == null || !account.equals(before.profileId())
                    || !NAME.matcher(before.profileName()).matches()) {
                return finish(ticket, account, Outcome.AUTH_REQUIRED, null);
            }
            byte[] proof = new byte[16];
            random.nextBytes(proof);
            String serverId = HexFormat.of().formatHex(proof);
            URI uri = sessions.withSession((identity, accessToken) -> {
                if (!before.equals(identity) || accessToken == null || accessToken.isBlank()) {
                    throw new AuthUnavailable();
                }
                requireLive(ticket, account);
                joinTransport.join(account, accessToken, serverId);
                requireLive(ticket, account);
                return URI.create("https://optifine.net/capeChange?u=" + compact(account)
                        + "&n=" + before.profileName() + "&s=" + serverId);
            });
            if (!before.equals(sessions.currentSession())) return finish(ticket, account, Outcome.AUTH_REQUIRED, null);
            return finish(ticket, account, Outcome.READY, uri);
        } catch (AuthUnavailable | GameSessionTokenUnavailableException unavailable) {
            return finish(ticket, account, Outcome.AUTH_REQUIRED, null);
        } catch (Expired unavailable) {
            return finish(ticket, account, Outcome.EXPIRED, null);
        } catch (Cancelled unavailable) {
            return new Result(Outcome.CANCELLED);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return finish(ticket, account, Outcome.FAILED, null);
        } catch (Exception unavailable) {
            return finish(ticket, account, Outcome.FAILED, null);
        }
    }

    private synchronized Result finish(long ticket, UUID account, Outcome outcome, URI uri) {
        if (ticket != generation || !Objects.equals(accountId, account)) return new Result(Outcome.CANCELLED);
        if (expired()) outcome = Outcome.EXPIRED;
        preparing = false;
        ready = outcome == Outcome.READY ? uri : null;
        readyName = outcome == Outcome.READY ? sessions.currentSession().profileName() : null;
        return new Result(outcome);
    }

    private synchronized void requireLive(long ticket, UUID account) {
        if (ticket != generation || !Objects.equals(accountId, account)) throw new Cancelled();
        if (expired()) throw new Expired();
        if (!currentAccount(account)) throw new AuthUnavailable();
    }

    private boolean currentAccount(UUID account) {
        try {
            GameSessionTokenSource.SessionIdentity identity = sessions.currentSession();
            return identity != null && account.equals(identity.profileId());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static JoinTransport httpTransport() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return (profileId, accessToken, serverId) -> {
            JsonObject body = new JsonObject();
            body.addProperty("accessToken", accessToken);
            body.addProperty("selectedProfile", compact(profileId));
            body.addProperty("serverId", serverId);
            HttpRequest request = HttpRequest.newBuilder(JOIN_ENDPOINT)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 204) throw new IOException("join rejected");
        };
    }

    private static String compact(UUID value) {
        return value.toString().replace("-", "");
    }

    private static final class AuthUnavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
    private static final class Expired extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
    private static final class Cancelled extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
