package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.storage.*;
import java.util.UUID;
import java.util.Optional;
import java.net.URI;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

final class CapeTestComposition {
    static CapeProviderCoordinator create(GameSessionTokenSource tokens, NclSkinsStorage storage,
            TextureCache textures, PlayerAppearanceSink<?> sink, ClientExecutor client, Executor worker,
            OptifineCapeReader reader) {
        return create(tokens, storage, textures, sink, client, worker, reader, new SkinMcCapeReader(), (id, cape) -> Optional.empty());
    }
    static CapeProviderCoordinator create(GameSessionTokenSource tokens, NclSkinsStorage storage,
            TextureCache textures, PlayerAppearanceSink<?> sink, ClientExecutor client, Executor worker,
            BiFunction<UUID, String, Optional<URI>> official) {
        return create(tokens, storage, textures, sink, client, worker, new OptifineCapeReader(), new SkinMcCapeReader(), official);
    }
    static CapeProviderCoordinator create(GameSessionTokenSource tokens, NclSkinsStorage storage,
            TextureCache textures, PlayerAppearanceSink<?> sink, ClientExecutor client, Executor worker,
            OptifineCapeReader reader, BiFunction<UUID, String, Optional<URI>> official) {
        return create(tokens, storage, textures, sink, client, worker, reader, new SkinMcCapeReader(), official);
    }
    static CapeProviderCoordinator create(GameSessionTokenSource tokens, NclSkinsStorage storage,
            TextureCache textures, PlayerAppearanceSink<?> sink, ClientExecutor client, Executor worker,
            OptifineCapeReader reader, SkinMcCapeReader skinMc, BiFunction<UUID, String, Optional<URI>> official) {
        return new CapeProviderCoordinator(tokens, new AccountAppearanceStorageAdapter(storage), new LibraryStorageAdapter(storage),
                new ProviderTextureStorageAdapter(storage, textures), sink, client, worker, reader, skinMc, official);
    }
}
