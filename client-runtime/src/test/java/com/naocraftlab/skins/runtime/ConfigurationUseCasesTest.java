package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.config.ClientConfiguration;
import com.naocraftlab.skins.core.config.ServerConfiguration;
import com.naocraftlab.skins.core.config.MenuPreviewPlacement;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ConfigurationUseCasesTest {
    private final SpyStore store = new SpyStore();
    private final Path startup = Path.of("/startup");
    private final ClientConfiguration initial = ClientConfiguration.defaults();
    private final ClientConfigurationService service = new ClientConfigurationService(
            store, initial, startup, startup);

    @Test
    void remoteAndRealmsNeverReadOrWriteServerEvenWithInjectedDraft() {
        var access = ServerConfigurationAccess.from(true, false);
        var session = service.openSession(access);
        assertTrue(session.server().isEmpty());
        assertFalse(session.serverAccess().visible());
        service.save(access, initial, Optional.of(ServerConfiguration.defaults()));
        assertEquals(List.of("client"), store.calls);
    }

    @Test
    void constructionAndCancelledDraftHaveNoStorageEffects() {
        assertTrue(store.calls.isEmpty());
        var session = service.openSession(ServerConfigurationAccess.REMOTE_SERVER);
        var draft = session.client().withTitleScreenPreview(MenuPreviewPlacement.LEFT);
        assertNotEquals(draft, service.client());
        assertTrue(store.calls.isEmpty());
        assertEquals(initial, service.openSession(ServerConfigurationAccess.REMOTE_SERVER).client());
    }

    @Test
    void localSessionsReadServerOnlyOnOpenAndKeepRestartFlags() {
        for (var access : List.of(ServerConfigurationAccess.BEFORE_SERVER_START,
                ServerConfigurationAccess.INTEGRATED_SERVER_RUNNING)) {
            var session = service.openSession(access);
            assertEquals(Optional.of(ServerConfiguration.defaults()), session.server());
            assertEquals(access, session.serverAccess());
            assertEquals(access == ServerConfigurationAccess.INTEGRATED_SERVER_RUNNING,
                    session.serverAccess().restartRequired());
        }
        assertEquals(List.of("loadServer", "loadServer"), store.calls);
    }

    @Test
    void persistPrecedesPublicationAndServerSaveSeesPublishedClient() {
        var changed = initial.withTitleScreenPreview(MenuPreviewPlacement.LEFT);
        store.beforeClient = () -> assertSame(initial, service.client());
        store.beforeServer = () -> assertSame(changed, service.client());
        service.save(ServerConfigurationAccess.BEFORE_SERVER_START, changed,
                Optional.of(ServerConfiguration.defaults()));
        assertEquals(List.of("client", "server"), store.calls);
        assertSame(changed, store.persisted);
        assertSame(changed, service.client());
    }

    @Test
    void clientFailureDoesNotPublishOrAttemptServerAndCanRetry() {
        var failure = new IllegalStateException("client failed");
        store.beforeClient = () -> { throw failure; };
        var changed = initial.withPauseMenuPreview(MenuPreviewPlacement.OFF);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.save(
                ServerConfigurationAccess.BEFORE_SERVER_START, changed,
                Optional.of(ServerConfiguration.defaults()))));
        assertSame(initial, service.client());
        assertNull(store.persisted);
        assertEquals(List.of("client"), store.calls);
        store.beforeClient = () -> {};
        service.save(ServerConfigurationAccess.BEFORE_SERVER_START, changed, Optional.empty());
        assertSame(changed, service.client());
    }

    @Test
    void serverFailureKeepsClientPersistedPublishedAndRootStableOnReopen() {
        var changed = initial.withDataDirectory("/next");
        var failure = new IllegalStateException("server failed");
        store.beforeServer = () -> { throw failure; };
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.save(
                ServerConfigurationAccess.INTEGRATED_SERVER_RUNNING, changed,
                Optional.of(ServerConfiguration.defaults()))));
        assertSame(changed, service.client());
        assertSame(changed, store.persisted);
        assertEquals(startup, service.activeDataRoot());
        var reopened = service.openSession(ServerConfigurationAccess.REMOTE_SERVER);
        assertSame(changed, reopened.client());
        assertEquals(startup, reopened.activeDataRoot());
        assertEquals(startup, reopened.defaultDataRoot());
        assertEquals(List.of("client", "server"), store.calls);
    }

    private static final class SpyStore implements ConfigurationStore {
        private final List<String> calls = new ArrayList<>();
        private Runnable beforeClient = () -> {};
        private Runnable beforeServer = () -> {};
        private ClientConfiguration persisted;

        public ClientConfiguration loadClient() {
            throw new AssertionError("Application must use supplied startup snapshot");
        }
        public void saveClient(ClientConfiguration value) {
            calls.add("client");
            beforeClient.run();
            persisted = value;
        }
        public ServerConfiguration loadServer() {
            calls.add("loadServer");
            return ServerConfiguration.defaults();
        }
        public void saveServer(ServerConfiguration value) {
            calls.add("server");
            beforeServer.run();
        }
    }
}
