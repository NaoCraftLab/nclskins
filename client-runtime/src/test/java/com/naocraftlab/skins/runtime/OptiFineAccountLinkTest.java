package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.GameSessionTokenSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptiFineAccountLinkTest {
    private static final UUID ACCOUNT = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");

    @Test void preparesOnlyAfterJoinAndRejectsSecondClick() {
        var queue = new ArrayList<Runnable>();
        var session = new Session(ACCOUNT, "Player_1");
        var calls = new ArrayList<String>();
        var link = new OptiFineAccountLink(session, queue::add, (id, token, proof) -> {
            assertEquals(ACCOUNT, id);
            assertEquals("current-token", token);
            assertEquals(32, proof.length());
            calls.add(proof);
        }, new SecureRandom(), new MutableClock());
        var first = link.begin(ACCOUNT);
        assertTrue(link.preparing());
        assertNull(link.readyUri(ACCOUNT));
        assertEquals(OptiFineAccountLink.Outcome.CANCELLED, link.begin(ACCOUNT).join().outcome());
        assertEquals(1, queue.size());
        queue.remove(0).run();
        assertEquals(OptiFineAccountLink.Outcome.READY, first.join().outcome());
        assertEquals(1, calls.size());
        assertEquals("https://optifine.net/capeChange?u=0123456789abcdef0123456789abcdef&n=Player_1&s=" + calls.get(0),
                link.readyUri(ACCOUNT).toString());
        session.identity = new GameSessionTokenSource.SessionIdentity(ACCOUNT, "Renamed");
        assertNull(link.readyUri(ACCOUNT));
        link.cancel();
        assertNull(link.readyUri(ACCOUNT));
    }

    @Test void rejectionNeverExposesUnconfirmedLinkAndRequiresNewAction() {
        var queue = new ArrayList<Runnable>();
        var session = new Session(ACCOUNT, "Player_1");
        var link = new OptiFineAccountLink(session, queue::add, (id, token, proof) -> {
            throw new IOException("rejected");
        }, new SecureRandom(), new MutableClock());
        var first = link.begin(ACCOUNT);
        queue.remove(0).run();
        assertEquals(OptiFineAccountLink.Outcome.FAILED, first.join().outcome());
        assertNull(link.readyUri(ACCOUNT));
        assertFalse(link.preparing());
        link.begin(ACCOUNT);
        assertEquals(1, queue.size());
    }

    @Test void missingLicensedTokenStopsBeforeJoin() {
        var queue = new ArrayList<Runnable>();
        var session = new Session(ACCOUNT, "Player_1");
        session.token = "";
        var link = new OptiFineAccountLink(session, queue::add,
                (id, token, proof) -> { throw new AssertionError("join must not run"); },
                new SecureRandom(), new MutableClock());
        var attempt = link.begin(ACCOUNT);
        queue.remove(0).run();
        assertEquals(OptiFineAccountLink.Outcome.AUTH_REQUIRED, attempt.join().outcome());
        assertNull(link.readyUri(ACCOUNT));
    }

    @Test void cancellationAndAccountSwitchFencePendingJoin() {
        var queue = new ArrayList<Runnable>();
        var session = new Session(ACCOUNT, "Player_1");
        var link = new OptiFineAccountLink(session, queue::add, (id, token, proof) -> {
            session.identity = new GameSessionTokenSource.SessionIdentity(new UUID(0, 2), "Other");
        }, new SecureRandom(), new MutableClock());
        var first = link.begin(ACCOUNT);
        queue.remove(0).run();
        assertEquals(OptiFineAccountLink.Outcome.AUTH_REQUIRED, first.join().outcome());
        assertNull(link.readyUri(ACCOUNT));
        session.identity = new GameSessionTokenSource.SessionIdentity(ACCOUNT, "Player_1");
        var second = link.begin(ACCOUNT);
        link.cancel();
        queue.remove(0).run();
        assertEquals(OptiFineAccountLink.Outcome.CANCELLED, second.join().outcome());
    }

    @Test void deadlineStartsAtClickAndInvalidatesPreparedLink() {
        var queue = new ArrayList<Runnable>();
        var clock = new MutableClock();
        var link = new OptiFineAccountLink(new Session(ACCOUNT, "Player_1"), queue::add,
                (id, token, proof) -> { }, new SecureRandom(), clock);
        var first = link.begin(ACCOUNT);
        clock.now = clock.now.plusSeconds(121);
        queue.remove(0).run();
        assertEquals(OptiFineAccountLink.Outcome.EXPIRED, first.join().outcome());
        assertNull(link.readyUri(ACCOUNT));
        var second = link.begin(ACCOUNT);
        queue.remove(0).run();
        assertEquals(OptiFineAccountLink.Outcome.READY, second.join().outcome());
        clock.now = clock.now.plusSeconds(121);
        assertNull(link.readyUri(ACCOUNT));
    }

    private static final class Session implements GameSessionTokenSource {
        private SessionIdentity identity;
        private String token = "current-token";

        private Session(UUID account, String name) {
            identity = new SessionIdentity(account, name);
        }

        @Override public SessionIdentity currentSession() { return identity; }

        @Override public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
            return request.execute(token);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-24T00:00:00Z");

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
