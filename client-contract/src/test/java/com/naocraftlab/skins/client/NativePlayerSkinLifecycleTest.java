package com.naocraftlab.skins.client;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativePlayerSkinLifecycleTest {
    @Test
    void unknownAndReadyTexturesCanRenderButPendingAndFailedTexturesCannot() {
        String location = "nclskins:test/lifecycle/basic";

        assertTrue(NativePlayerSkinLifecycle.isReady(location));
        assertEquals(Optional.empty(), NativePlayerSkinLifecycle.managedState(location));
        NativePlayerSkinLifecycle.Registration registration =
                NativePlayerSkinLifecycle.pending(location);
        assertEquals(NativePlayerSkinLifecycle.State.PENDING, registration.state());
        assertEquals(
                Optional.of(NativePlayerSkinLifecycle.State.PENDING),
                NativePlayerSkinLifecycle.managedState(location));
        assertFalse(NativePlayerSkinLifecycle.isReady(location));

        registration.ready();
        assertEquals(NativePlayerSkinLifecycle.State.READY, registration.state());
        assertEquals(
                Optional.of(NativePlayerSkinLifecycle.State.READY),
                NativePlayerSkinLifecycle.managedState(location));
        assertTrue(NativePlayerSkinLifecycle.isReady(location));

        NativePlayerSkinLifecycle.Registration failed =
                NativePlayerSkinLifecycle.pending(location);
        failed.failed();
        assertFalse(NativePlayerSkinLifecycle.isReady(location));
        assertEquals(
                Optional.of(NativePlayerSkinLifecycle.State.FAILED),
                NativePlayerSkinLifecycle.managedState(location));
        failed.retire();
        assertTrue(NativePlayerSkinLifecycle.isReady(location));
        assertEquals(Optional.empty(), NativePlayerSkinLifecycle.managedState(location));
    }

    @Test
    void lateCompletionCannotPublishOverANewerRegistration() {
        String location = "nclskins:test/lifecycle/generation";
        NativePlayerSkinLifecycle.Registration stale =
                NativePlayerSkinLifecycle.pending(location);
        NativePlayerSkinLifecycle.Registration current =
                NativePlayerSkinLifecycle.pending(location);

        stale.ready();
        assertFalse(NativePlayerSkinLifecycle.isReady(location));
        assertEquals(NativePlayerSkinLifecycle.State.PENDING, current.state());

        current.ready();
        assertTrue(NativePlayerSkinLifecycle.isReady(location));
        current.retire();
    }

    @Test
    void retiredRegistrationCannotBeResurrectedByLateCompletion() {
        String location = "nclskins:test/lifecycle/retired";
        NativePlayerSkinLifecycle.Registration registration =
                NativePlayerSkinLifecycle.pending(location);

        registration.retire();
        registration.ready();

        assertEquals(NativePlayerSkinLifecycle.State.RETIRED, registration.state());
        assertTrue(NativePlayerSkinLifecycle.isReady(location));
    }

    @Test
    void concurrentRetireAndCompletionCannotLeaveARegistrationPublished()
            throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            String location = "nclskins:test/lifecycle/race/" + attempt;
            NativePlayerSkinLifecycle.Registration registration =
                    NativePlayerSkinLifecycle.pending(location);
            CountDownLatch start = new CountDownLatch(1);
            Thread completion = new Thread(() -> {
                await(start);
                registration.ready();
            });
            Thread retirement = new Thread(() -> {
                await(start);
                registration.retire();
            });

            completion.start();
            retirement.start();
            start.countDown();
            completion.join();
            retirement.join();

            assertEquals(NativePlayerSkinLifecycle.State.RETIRED, registration.state());
            assertTrue(NativePlayerSkinLifecycle.isReady(location));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("lifecycle race did not start");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
