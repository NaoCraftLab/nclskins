package com.naocraftlab.skins.runtime.composition;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.api.MinecraftProfileApi;
import com.naocraftlab.skins.core.api.PublicPlayerSkinClient;
import com.naocraftlab.skins.core.api.PublicSkinImageAdapter;
import com.naocraftlab.skins.core.importing.PublicProfileLookup;
import com.naocraftlab.skins.core.api.ProfileApi;
import com.naocraftlab.skins.core.api.ProfileSessionAdapter;
import com.naocraftlab.skins.core.config.ClientConfiguration;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.service.*;
import com.naocraftlab.skins.core.storage.*;
import com.naocraftlab.skins.diagnostics.DiagnosticSink;
import com.naocraftlab.skins.runtime.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class ClientCompositionRoot {
    private ClientCompositionRoot() {}

    public static <C> ClientApplicationHost<C> createApplication(
            ClientCapabilitySet capabilities, TextResolver textResolver, Path dataRoot,
            Supplier<ClientConfiguration> configurationSource, DiagnosticSink diagnostics,
            Runnable closeNativeResources) {
        Objects.requireNonNull(closeNativeResources, "closeNativeResources");
        return new ClientApplicationHost<>(createRuntime(capabilities, textResolver, dataRoot,
                configurationSource, diagnostics), closeNativeResources);
    }

    public static ClientRuntime createRuntime(
            ClientCapabilitySet capabilities, TextResolver textResolver, Path dataRoot,
            Supplier<ClientConfiguration> configurationSource, DiagnosticSink diagnostics) {
        return createRuntime(capabilities, textResolver, dataRoot, configurationSource, diagnostics,
                ClientCompositionRoot::newWorker);
    }

    static ClientRuntime createRuntime(
            ClientCapabilitySet capabilities, TextResolver textResolver, Path dataRoot,
            Supplier<ClientConfiguration> configurationSource, DiagnosticSink diagnostics,
            WorkerFactory workers) {
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(textResolver, "textResolver");
        Objects.requireNonNull(dataRoot, "dataRoot");
        Objects.requireNonNull(configurationSource, "configurationSource");
        Objects.requireNonNull(diagnostics, "diagnostics");
        try (StartupResources resources = new StartupResources()) {
            Worker worker = resources.worker(workers, "nclskins-client-runtime");
            Worker reconciliation = resources.worker(workers, "nclskins-appearance-reconciliation");
            Worker session = resources.worker(workers, "nclskins-session-activity");
            Clock clock = Clock.systemUTC();
            NclSkinsStorage storage = new NclSkinsStorage(dataRoot, new PngValidator(), clock);
            OperationsGraph graph = operations(capabilities.session(), new MinecraftProfileApi(),
                    storage, capabilities.resourcePackAccess(), clock, null,
                    Optional.of(new PublicPlayerSkinClient(capabilities.signedTextureVerification())));
            Worker capes = resources.worker(workers, "nclskins-public-cape");
            CapeProviderCoordinator coordinator = new CapeProviderCoordinator(capabilities.session(),
                    graph.appearances(), graph.assets(), graph.textures(), capabilities.appearanceInstall(),
                    capabilities.clientExecutor(), capes.executor(), new OptifineCapeReader(),
                    new SkinMcCapeReader(), graph.operations()::verifiedOfficialCapeUri);
            resources.add(coordinator::close);
            graph.operations().attachCapeProviders(coordinator, capes.ownedExecutor());
            SignedProfileResolver<AcknowledgedAppearanceAssets> resolver =
                    new DeterministicAppearanceAssetResolver(capabilities.session(), storage,
                            graph.textureCache(), worker.executor());
            AppearanceRefreshCoordinator<AcknowledgedAppearanceAssets> refresh =
                    new AppearanceRefreshCoordinator<>(capabilities.clientExecutor(),
                            resolver,
                            capabilities.appearanceInstall(), diagnostics);
            resources.add(refresh::close);
            ClientRuntime runtime = new ClientRuntime(graph.operations(), capabilities.clientExecutor(),
                    capabilities.nativeFileDialog(), worker.executor(), worker.ownedExecutor(),
                    reconciliation.executor(), reconciliation.ownedExecutor(),
                    session.executor(), session.ownedExecutor(), textResolver,
                    Optional.of(capabilities.currentAppearance()), Optional.of(refresh),
                    Optional.of(capabilities.modelParts()), Optional.of(capabilities.serverSignal()),
                    ServerAppearanceReadinessCoordinator.DelayScheduler.system(), diagnostics);
            runtime.useOptiFineAccountLink(new OptiFineAccountLink(capabilities.session(), session.executor()));
            runtime.useConfigurationSource(configurationSource);
            runtime.useSkinExtensionEnvironmentSource(capabilities.skinExtensionEnvironment());
            resources.transfer();
            return runtime;
        }
    }

    public static OperationsGraph operations(GameSessionTokenSource tokens, ProfileApi api,
            NclSkinsStorage storage, SkinCatalogSource catalog, Clock clock,
            DefaultClientOperations.OfficialSkinTextureSource officialTextures) {
        return operations(tokens, api, storage, catalog, clock, officialTextures, Optional.empty());
    }

    private static OperationsGraph operations(GameSessionTokenSource tokens, ProfileApi api,
            NclSkinsStorage storage, SkinCatalogSource catalog, Clock clock,
            DefaultClientOperations.OfficialSkinTextureSource officialTextures,
            Optional<PublicProfileLookup> players) {
        var profile = new ProfileSessionAdapter(api);
        var appearances = new AccountAppearanceStorageAdapter(storage);
        var bootstrap = new AccountBootstrapAdapter(storage);
        var assets = new LibraryStorageAdapter(storage);
        var library = new LibraryService(assets, assets, clock);
        var gate = new RemoteSessionGate();
        var sessions = new SessionValidationService(profile, gate);
        var mutationStorage = new MutationStorageAdapter(storage);
        var mutations = new AppearanceMutationService(profile, mutationStorage, mutationStorage, gate, sessions);
        var textureCache = new TextureCache(storage);
        var textures = new ProviderTextureStorageAdapter(storage, textureCache);
        LibraryCatalogAdapter.CurrentAccount current = () ->
                Objects.requireNonNull(tokens.currentSession(), "current session").profileId();
        var preferences = new UiPreferencesStorageAdapter(storage, current);
        var accounts = new LibraryCatalogAdapter(library, assets, assets, current);
        var prepared = new PreparedCatalogService(catalog, accounts);
        var imports = new PublicSkinImportService(players, new PublicSkinImageAdapter(textureCache),
                prepared::loadCatalogSkin);
        var external = new ExternalAppearanceImportService(new ExternalImportSourceAdapter(imports, catalog),
                prepared, new LibraryExternalImportAdapter(library, accounts));
        var delivery = new AccountDeliveryService(appearances, clock);
        var operations = new DefaultClientOperations(tokens, profile, appearances, bootstrap, assets, assets,
                preferences, new LocalCapeImportAdapter(), catalog, clock, library, gate, sessions, mutations,
                textures, prepared, imports, external,
                officialTextures != null ? officialTextures : skin -> textures.load(skin.textureUri()),
                new OfficialSkinClassifier(catalog), delivery,
                new AccountMutationExecutor(appearances, assets, clock, delivery));
        return new OperationsGraph(operations, appearances, assets, textures, textureCache);
    }

    public record OperationsGraph(DefaultClientOperations operations, AccountAppearanceStore appearances,
            AssetStorePort assets, ProviderTextureStore textures, TextureCache textureCache) {}

    private static Worker newWorker(String name) {
        if (name.equals("nclskins-public-cape")) {
            AtomicInteger index = new AtomicInteger();
            return new Worker(new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(32), task -> thread(task, name + "-" + index.incrementAndGet())), true);
        }
        return new Worker(Executors.newSingleThreadExecutor(task -> thread(task, name)), true);
    }

    private static Thread thread(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    record Worker(ExecutorService executor, boolean owned) {
        Worker { Objects.requireNonNull(executor, "executor"); }
        ExecutorService ownedExecutor() { return owned ? executor : null; }
    }

    @FunctionalInterface
    interface WorkerFactory { Worker create(String name); }

    static final class StartupResources implements AutoCloseable {
        private final ArrayDeque<Runnable> cleanup = new ArrayDeque<>();
        Worker worker(WorkerFactory factory, String name) {
            Worker worker = Objects.requireNonNull(factory.create(name), "worker");
            if (worker.owned()) add(worker.executor()::shutdownNow);
            return worker;
        }
        void add(Runnable close) { cleanup.push(close); }
        void transfer() { cleanup.clear(); }
        @Override public void close() {
            Throwable failure = null;
            while (!cleanup.isEmpty()) {
                try { cleanup.pop().run(); }
                catch (RuntimeException | Error closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
            if (failure instanceof Error error) throw error;
            if (failure instanceof RuntimeException exception) throw exception;
        }
    }
}
