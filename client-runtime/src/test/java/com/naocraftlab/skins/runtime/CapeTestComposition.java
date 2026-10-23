package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.core.storage.*;
import java.util.UUID;
import java.util.Optional;
import java.net.URI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

final class CapeTestComposition {
    @FunctionalInterface
    interface ResponseFixture {
        OptifineCapeReader.Response get(URI uri, Duration timeout, long maxBytes) throws IOException;
    }

    static OptifineCapeReader.Transport fileTransport(ResponseFixture fixture) {
        return (uri, timeout, maxBytes, destination) -> {
            OptifineCapeReader.Response response = fixture.get(uri, timeout, maxBytes);
            if (response.status() == 200) {
                Files.write(destination, response.bytes());
            }
            return new OptifineCapeReader.FileResponse(response.status(), response.bytes().length,
                    response.headers());
        };
    }

    static OptifineCapeReader optifine(NclSkinsStorage storage, ResponseFixture fixture,
            Clock clock) {
        Path scratch = storage.layout().root().resolve("test-optifine-staging");
        return new OptifineCapeReader(fileTransport(fixture), scratch, clock);
    }

    static SkinMcCapeReader skinMc(NclSkinsStorage storage, ResponseFixture fixture,
            Clock clock) {
        Path scratch = storage.layout().root().resolve("test-skinmc-staging");
        return new SkinMcCapeReader(fileTransport(fixture), fileTransport(fixture)::getToFile,
                scratch, clock, System::nanoTime);
    }

    static CapeProviderCoordinator create(GameSessionTokenSource tokens, NclSkinsStorage storage,
            TextureCache textures, PlayerAppearanceSink<?> sink, ClientExecutor client, Executor worker,
            OptifineCapeReader reader) {
        return create(tokens, storage, textures, sink, client, worker, reader,
                new SkinMcCapeReader(storage.layout().root().resolve("test-skinmc-staging")),
                (id, cape) -> Optional.empty());
    }
    static CapeProviderCoordinator create(GameSessionTokenSource tokens, NclSkinsStorage storage,
            TextureCache textures, PlayerAppearanceSink<?> sink, ClientExecutor client, Executor worker,
            BiFunction<UUID, String, Optional<URI>> official) {
        return create(tokens, storage, textures, sink, client, worker,
                new OptifineCapeReader(storage.layout().root().resolve("test-optifine-staging")),
                new SkinMcCapeReader(storage.layout().root().resolve("test-skinmc-staging")), official);
    }
    static CapeProviderCoordinator create(GameSessionTokenSource tokens, NclSkinsStorage storage,
            TextureCache textures, PlayerAppearanceSink<?> sink, ClientExecutor client, Executor worker,
            OptifineCapeReader reader, BiFunction<UUID, String, Optional<URI>> official) {
        return create(tokens, storage, textures, sink, client, worker, reader,
                new SkinMcCapeReader(storage.layout().root().resolve("test-skinmc-staging")), official);
    }
    static CapeProviderCoordinator create(GameSessionTokenSource tokens, NclSkinsStorage storage,
            TextureCache textures, PlayerAppearanceSink<?> sink, ClientExecutor client, Executor worker,
            OptifineCapeReader reader, SkinMcCapeReader skinMc, BiFunction<UUID, String, Optional<URI>> official) {
        return new CapeProviderCoordinator(tokens, new AccountAppearanceStorageAdapter(storage), new LibraryStorageAdapter(storage),
                new ProviderTextureStorageAdapter(storage, textures), sink, client, worker, reader, skinMc, official);
    }

    static CapeProviderCoordinator create(GameSessionTokenSource tokens, NclSkinsStorage storage,
            TextureCache textures, PlayerAppearanceSink<?> sink, ClientExecutor client, Executor worker,
            OptifineCapeReader reader, SkinMcCapeReader skinMc, MinecraftCapesCapeReader minecraftCapes,
            BiFunction<UUID, String, Optional<URI>> official) {
        return new CapeProviderCoordinator(tokens, new AccountAppearanceStorageAdapter(storage), new LibraryStorageAdapter(storage),
                new ProviderTextureStorageAdapter(storage, textures), sink, client, worker, reader, skinMc,
                minecraftCapes, official);
    }
}
