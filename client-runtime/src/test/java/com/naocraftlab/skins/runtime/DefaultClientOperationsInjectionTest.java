package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.SkinCatalogSource;
import com.naocraftlab.skins.core.service.*;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class DefaultClientOperationsInjectionTest {
    @TempDir Path directory;

    @Test
    void readyPortsAreUsedWithoutConstructingOrInitializingProductionStorage() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AccountBootstrapPort bootstrap = (AccountBootstrapPort) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{AccountBootstrapPort.class}, (proxy, method, args) -> {
                    assertEquals("initialize", method.getName());
                    calls.incrementAndGet();
                    return null;
                });
        var profile = unused(ProfileSessionPort.class);
        var appearances = unused(AccountAppearanceStore.class);
        var assets = unused(AssetStorePort.class);
        var state = unused(LibraryStatePort.class);
        var gate = new RemoteSessionGate();
        var sessions = new SessionValidationService(profile, gate);
        var mutationStore = unused(MutationGuard.class);
        var clock = Clock.systemUTC();
        var library = new LibraryService(state, assets, clock);
        SkinCatalogSource sources = (collection, skin, model) -> { throw new AssertionError("catalog accessed"); };
        var prepared = new PreparedCatalogService(sources, unused(CatalogAccountAccess.class));
        var imports = unused(PublicSkinImports.class);
        var external = new ExternalAppearanceImportService(unused(ExternalImportSourceAccess.class), prepared,
                unused(ExternalImportCommit.class));
        var delivery = new AccountDeliveryService(appearances, clock);
        var operations = new DefaultClientOperations(unused(GameSessionTokenSource.class), profile,
                appearances, bootstrap, assets, state, unused(UiPreferencesPort.class),
                unused(LocalCapeImportSource.class), sources, clock, library, gate, sessions,
                new AppearanceMutationService(profile, mutationStore, unused(MutationTextureIdentity.class), gate, sessions),
                unused(ProviderTextureStore.class), prepared, imports, external,
                skin -> { throw new AssertionError("official texture accessed"); },
                new OfficialSkinClassifier(sources), delivery,
                new AccountMutationExecutor(appearances, assets, clock, delivery));
        assertEquals(0, calls.get());
        operations.verifyStorageAccess();
        assertEquals(1, calls.get());
        assertFalse(Files.exists(directory.resolve("unused")));
    }

    @SuppressWarnings("unchecked")
    private static <T> T unused(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            throw new AssertionError("Unexpected port call: " + type.getSimpleName() + "." + method.getName());
        });
    }
}
