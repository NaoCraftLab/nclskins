package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import java.nio.charset.StandardCharsets

import static org.junit.jupiter.api.Assertions.*

final class UpdateCatalogDeploymentProbeTest {
    private final Map<String, byte[]> expected = [
            'updates/v1/catalog.json': '{"schemaVersion":1}\n'.getBytes(StandardCharsets.UTF_8),
            'updates/v1/native/neoforge-26.2.json': '{}\n'.getBytes(StandardCharsets.UTF_8)
    ]

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted()
    }

    @Test
    void successComparesEveryEndpointWithDeterministicCacheBusting() {
        List<URI> requests = []
        UpdateCatalogDeploymentProbe.verify(expected, { URI uri ->
            requests.add(uri)
            String relative = uri.path.substring('/nclskins/'.length())
            new UpdateCatalogDeploymentProbe.ProbeResponse(200, expected[relative])
        } as UpdateCatalogDeploymentProbe.Fetcher, { } as UpdateCatalogDeploymentProbe.Sleeper, 2)

        assertEquals(expected.size(), requests.size())
        assertTrue(requests.every { URI uri ->
            uri.scheme == 'https' && uri.host == 'naocraftlab.github.io' &&
                    uri.rawQuery ==~ /nclskins_probe=1-[0-9a-f]{12}/ &&
                    uri.userInfo == null
        })
    }

    @Test
    void staleContentIsPolledBoundedlyAndCanConverge() {
        int calls = 0
        int pauses = 0
        UpdateCatalogDeploymentProbe.verify(expected, { URI uri ->
            calls++
            String relative = uri.path.substring('/nclskins/'.length())
            byte[] body = uri.query.startsWith('nclskins_probe=1-')
                    ? 'stale'.bytes : expected[relative]
            new UpdateCatalogDeploymentProbe.ProbeResponse(200, body)
        } as UpdateCatalogDeploymentProbe.Fetcher, {
            pauses++
        } as UpdateCatalogDeploymentProbe.Sleeper, 3)

        assertEquals(expected.size() * 2, calls)
        assertEquals(1, pauses)
    }

    @Test
    void persistentStaleMissingAndTimeoutStatesFailWithSafeReasons() {
        IllegalStateException stale = assertThrows(IllegalStateException) {
            UpdateCatalogDeploymentProbe.verify(expected, { URI ignored ->
                new UpdateCatalogDeploymentProbe.ProbeResponse(200, 'old'.bytes)
            } as UpdateCatalogDeploymentProbe.Fetcher,
            { } as UpdateCatalogDeploymentProbe.Sleeper, 2)
        }
        assertTrue(stale.message.contains('=stale'))
        assertFalse(stale.message.contains('old'))

        IllegalStateException missing = assertThrows(IllegalStateException) {
            UpdateCatalogDeploymentProbe.verify(expected, { URI ignored ->
                new UpdateCatalogDeploymentProbe.ProbeResponse(404, new byte[0])
            } as UpdateCatalogDeploymentProbe.Fetcher,
            { } as UpdateCatalogDeploymentProbe.Sleeper, 1)
        }
        assertTrue(missing.message.contains('=missing'))

        int timeouts = 0
        IllegalStateException timeout = assertThrows(IllegalStateException) {
            UpdateCatalogDeploymentProbe.verify(expected, { URI ignored ->
                timeouts++
                throw new IOException('timeout detail must stay private')
            } as UpdateCatalogDeploymentProbe.Fetcher,
            { } as UpdateCatalogDeploymentProbe.Sleeper, 2)
        }
        assertEquals(expected.size() * 2, timeouts)
        assertTrue(timeout.message.contains('=unavailable'))
        assertFalse(timeout.message.contains('timeout detail'))
    }

    @Test
    void interruptionRestoresFlagAndOversizedExpectationsAreRejected() {
        assertThrows(IllegalStateException) {
            UpdateCatalogDeploymentProbe.verify(expected, { URI ignored ->
                throw new InterruptedException('stop')
            } as UpdateCatalogDeploymentProbe.Fetcher,
            { } as UpdateCatalogDeploymentProbe.Sleeper, 1)
        }
        assertTrue(Thread.currentThread().isInterrupted())
        Thread.interrupted()

        Map<String, byte[]> oversized = [
                'updates/v1/catalog.json':
                        new byte[UpdateCatalogDeploymentProbe.MAX_BODY_BYTES + 1]
        ]
        assertThrows(IllegalArgumentException) {
            UpdateCatalogDeploymentProbe.verify(oversized,
                    { URI ignored -> null } as UpdateCatalogDeploymentProbe.Fetcher,
                    { } as UpdateCatalogDeploymentProbe.Sleeper, 1)
        }
    }
}
