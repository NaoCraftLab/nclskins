package com.naocraftlab.skins.server;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


final class AppearanceRefreshSignalProtocolTest {
    @Test
    void signalIsVersionedDirectionBoundAndCarriesNoData() {
        assertEquals("nclskins:appearance_refresh_v1", AppearanceRefreshSignalProtocol.CHANNEL);
        assertArrayEquals(new byte[0], AppearanceRefreshSignalProtocol.payload());
        assertTrue(AppearanceRefreshSignalProtocol.accepts(
                AppearanceRefreshSignalProtocol.Direction.CLIENT_TO_SERVER, new byte[0]));
        assertFalse(AppearanceRefreshSignalProtocol.accepts(
                AppearanceRefreshSignalProtocol.Direction.CLIENT_TO_SERVER, new byte[] {1}));
        assertFalse(AppearanceRefreshSignalProtocol.accepts(
                AppearanceRefreshSignalProtocol.Direction.SERVER_TO_CLIENT, new byte[0]));
        assertFalse(AppearanceRefreshSignalProtocol.accepts(
                AppearanceRefreshSignalProtocol.Direction.CLIENT_TO_SERVER, null));
    }

    @Test
    void dispatchesOneExistingRequestOnlyForAnExactValidSignal() {
        AtomicInteger requests = new AtomicInteger();

        assertTrue(AppearanceRefreshSignalProtocol.dispatch(
                AppearanceRefreshSignalProtocol.Direction.CLIENT_TO_SERVER,
                new byte[0],
                requests::incrementAndGet));
        assertFalse(AppearanceRefreshSignalProtocol.dispatch(
                AppearanceRefreshSignalProtocol.Direction.CLIENT_TO_SERVER,
                new byte[] {1},
                requests::incrementAndGet));
        assertFalse(AppearanceRefreshSignalProtocol.dispatch(
                AppearanceRefreshSignalProtocol.Direction.SERVER_TO_CLIENT,
                new byte[0],
                requests::incrementAndGet));
        assertEquals(1, requests.get());
    }

    @Test
    void burstDispatchAddsNoTransportSideDropsOrState() {
        AtomicInteger requests = new AtomicInteger();

        for (int index = 0; index < 1_000; index++) {
            assertTrue(AppearanceRefreshSignalProtocol.dispatch(
                    AppearanceRefreshSignalProtocol.Direction.CLIENT_TO_SERVER,
                    new byte[0],
                    requests::incrementAndGet));
        }

        assertEquals(1_000, requests.get());
    }
}
