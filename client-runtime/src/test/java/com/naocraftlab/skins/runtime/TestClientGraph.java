package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.api.ProfileApi;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.runtime.composition.ClientCompositionRoot;
import java.time.Clock;
import java.util.concurrent.Executor;

final class TestClientGraph {
    private final GameSessionTokenSource tokens;
    private final NclSkinsStorage storage;
    private final ClientCompositionRoot.OperationsGraph graph;

    private TestClientGraph(GameSessionTokenSource tokens, ProfileApi api, NclSkinsStorage storage,
            SkinCatalogSource catalog, Clock clock, DefaultClientOperations.OfficialSkinTextureSource textures) {
        this.tokens = tokens;
        this.storage = storage;
        graph = ClientCompositionRoot.operations(tokens, api, storage, catalog, clock, textures);
    }

    static TestClientGraph create(GameSessionTokenSource tokens, ProfileApi api, NclSkinsStorage storage,
            SkinCatalogSource catalog, Clock clock) {
        return new TestClientGraph(tokens, api, storage, catalog, clock, null);
    }

    static TestClientGraph create(GameSessionTokenSource tokens, ProfileApi api, NclSkinsStorage storage,
            BundledSkinSource catalog, Clock clock) {
        return create(tokens, api, storage, (SkinCatalogSource) catalog, clock);
    }

    static DefaultClientOperations operations(GameSessionTokenSource tokens, ProfileApi api, NclSkinsStorage storage,
            SkinCatalogSource catalog, Clock clock) {
        return create(tokens, api, storage, catalog, clock).operations();
    }

    static DefaultClientOperations operations(GameSessionTokenSource tokens, ProfileApi api, NclSkinsStorage storage,
            BundledSkinSource catalog, Clock clock) {
        return operations(tokens, api, storage, (SkinCatalogSource) catalog, clock);
    }

    static DefaultClientOperations operations(GameSessionTokenSource tokens, ProfileApi api, NclSkinsStorage storage,
            SkinCatalogSource catalog, Clock clock, DefaultClientOperations.OfficialSkinTextureSource textures) {
        return new TestClientGraph(tokens, api, storage, catalog, clock, textures).operations();
    }

    static DefaultClientOperations operations(GameSessionTokenSource tokens, ProfileApi api, NclSkinsStorage storage,
            BundledSkinSource catalog, Clock clock, DefaultClientOperations.OfficialSkinTextureSource textures) {
        return operations(tokens, api, storage, (SkinCatalogSource) catalog, clock, textures);
    }

    DefaultClientOperations operations() { return graph.operations(); }

    DeterministicAppearanceAssetResolver resolver(Executor worker) {
        return new DeterministicAppearanceAssetResolver(tokens, storage, graph.textureCache(), worker);
    }

    void attachProviders(PlayerAppearanceSink<?> sink, ClientExecutor client) {
        operations().attachCapeProviders(new CapeProviderCoordinator(tokens, graph.appearances(), graph.assets(),
                graph.textures(), sink, client, Runnable::run, new OptifineCapeReader(), new SkinMcCapeReader(),
                operations()::verifiedOfficialCapeUri), null);
    }
}
