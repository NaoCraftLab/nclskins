package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NativeTextureRegistrationTest {
    @Test
    void failedCreationClosesUnacquiredImageExactlyOnce() {
        NativeTextureRegistration owner = new NativeTextureRegistration(() -> true);
        Object image = new Object();
        AtomicInteger discarded = new AtomicInteger();
        owner.bind(image, discarded::incrementAndGet);
        owner.complete();
        owner.complete();
        owner.retire();
        assertNull(NativeTextureRegistration.forImage(image));
        assertEquals(1, discarded.get());
    }

    @Test
    void retiredImageCannotAcquireOrReleaseAReplacement() {
        AtomicBoolean current = new AtomicBoolean(true);
        NativeTextureRegistration owner = new NativeTextureRegistration(current::get);
        Object image = new Object();
        owner.bind(image, () -> fail("acquired image cleanup belongs to texture"));
        owner.retire();
        AtomicInteger registered = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        AtomicInteger discarded = new AtomicInteger();
        NativeTextureRegistration.forImage(image).acquire(
                registered::incrementAndGet, released::incrementAndGet, discarded::incrementAndGet);
        owner.complete();
        owner.retire();
        assertEquals(0, registered.get());
        assertEquals(0, released.get());
        assertEquals(1, discarded.get());
        assertNull(NativeTextureRegistration.forImage(image));
    }

    @Test
    void replacedCurrentnessRejectsQueuedAcquisitionWithoutExplicitRetire() {
        AtomicBoolean current = new AtomicBoolean(true);
        NativeTextureRegistration owner = new NativeTextureRegistration(current::get);
        Object image = new Object();
        owner.bind(image, () -> fail("acquired image cleanup belongs to texture"));
        current.set(false);
        AtomicInteger discarded = new AtomicInteger();
        owner.acquire(() -> fail("stale registration"), () -> fail("foreign release"), discarded::incrementAndGet);
        owner.complete();
        assertEquals(1, discarded.get());
    }

    @Test
    void completionDoesNotRetireAnAcquiredTexture() {
        NativeTextureRegistration owner = new NativeTextureRegistration(() -> true);
        Object image = new Object();
        owner.bind(image, () -> fail("acquired image cleanup belongs to texture"));
        AtomicInteger releases = new AtomicInteger();
        owner.acquire(() -> {}, releases::incrementAndGet, () -> fail("valid acquisition"));
        owner.complete();
        assertNull(NativeTextureRegistration.forImage(image));
        assertEquals(0, releases.get());
        owner.retire();
        owner.retire();
        assertEquals(1, releases.get());
    }

    @Test
    void reentrantRetirementReleasesOnlyAfterRegistrationReturns() {
        NativeTextureRegistration owner = new NativeTextureRegistration(() -> true);
        AtomicBoolean registered = new AtomicBoolean();
        AtomicInteger releases = new AtomicInteger();
        owner.acquire(() -> {
            owner.retire();
            assertEquals(0, releases.get());
            registered.set(true);
        }, () -> {
            assertTrue(registered.get());
            releases.incrementAndGet();
        }, () -> fail("valid acquisition"));
        owner.retire();
        assertEquals(1, releases.get());
    }

    @Test
    void inlineFailureDiscardsOnceAndPreservesOriginalFailure() {
        NativeTextureRegistration owner = new NativeTextureRegistration(() -> true);
        RuntimeException expected = new RuntimeException("synthetic register failure");
        AtomicInteger discarded = new AtomicInteger();
        assertSame(expected, assertThrows(RuntimeException.class, () -> owner.acquire(
                () -> { throw expected; }, () -> fail("no acquisition"), discarded::incrementAndGet)));
        owner.complete();
        owner.retire();
        assertEquals(1, discarded.get());
    }

    @Test
    void failedDiscardIsSuppressedOnTheOriginalRegistrationFailure() {
        NativeTextureRegistration owner = new NativeTextureRegistration(() -> true);
        RuntimeException original = new RuntimeException("synthetic register failure");
        RuntimeException cleanup = new RuntimeException("synthetic discard failure");
        assertSame(original, assertThrows(RuntimeException.class, () -> owner.acquire(
                () -> { throw original; }, () -> fail("no acquisition"), () -> { throw cleanup; })));
        assertArrayEquals(new Throwable[]{cleanup}, original.getSuppressed());
        owner.retire();
    }

    @Test
    void reentrantCompletionDoesNotLoseAcquiredCleanup() {
        NativeTextureRegistration owner = new NativeTextureRegistration(() -> true);
        Object image = new Object();
        owner.bind(image, () -> fail("acquired image cleanup belongs to texture"));
        AtomicInteger releases = new AtomicInteger();
        owner.acquire(owner::complete, releases::incrementAndGet, () -> fail("valid acquisition"));
        assertNull(NativeTextureRegistration.forImage(image));
        owner.retire();
        assertEquals(1, releases.get());
    }

    @Test
    void cleanupIsConsumedBeforeReentrantOrFailingRelease() {
        NativeTextureRegistration owner = new NativeTextureRegistration(() -> true);
        RuntimeException expected = new RuntimeException("synthetic release failure");
        AtomicInteger releases = new AtomicInteger();
        owner.acquire(() -> {}, () -> {
            releases.incrementAndGet();
            owner.retire();
            throw expected;
        }, () -> fail("valid acquisition"));
        assertSame(expected, assertThrows(RuntimeException.class, owner::retire));
        owner.retire();
        assertEquals(1, releases.get());
    }
}
