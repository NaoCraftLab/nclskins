package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.ExpectedAppearance;
import com.naocraftlab.skins.client.SignedProfileResolver;
import com.naocraftlab.skins.core.png.PngValidationException;
import com.naocraftlab.skins.core.storage.CachedTexture;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.naocraftlab.skins.core.storage.TextureCache;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;


public final class DeterministicAppearanceAssetResolver
        implements SignedProfileResolver<AcknowledgedAppearanceAssets> {
    private final GameSessionTokenSource tokenSource;
    private final NclSkinsStorage storage;
    private final TextureCache textures;
    private final Executor worker;
    private final RemoteTextureSource remoteTextures;

    DeterministicAppearanceAssetResolver(
            GameSessionTokenSource tokenSource,
            NclSkinsStorage storage,
            TextureCache textures,
            Executor worker) {
        this(tokenSource, storage, textures, worker, textures::get);
    }

    DeterministicAppearanceAssetResolver(
            GameSessionTokenSource tokenSource,
            NclSkinsStorage storage,
            TextureCache textures,
            Executor worker,
            RemoteTextureSource remoteTextures) {
        this.tokenSource = Objects.requireNonNull(tokenSource, "tokenSource");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.textures = Objects.requireNonNull(textures, "textures");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.remoteTextures = Objects.requireNonNull(remoteTextures, "remoteTextures");
    }

    @Override
    public CompletableFuture<Optional<ResolvedProfile<AcknowledgedAppearanceAssets>>> resolve(
            ExpectedAppearance expected) {
        Objects.requireNonNull(expected, "expected");
        if (!expected.profileId().equals(tokenSource.currentSession().profileId())) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.supplyAsync(() -> resolveProfile(expected), worker);
    }

    private Optional<ResolvedProfile<AcknowledgedAppearanceAssets>> resolveProfile(
            ExpectedAppearance expected) {
        try {
            Optional<AcknowledgedAppearanceAssets.Asset> skin = resolveSkin(expected);
            if ((expected.skinTexture().isPresent() || expected.localSkinSha256().isPresent())
                    && skin.isEmpty()) {
                return Optional.empty();
            }
            Optional<AcknowledgedAppearanceAssets.Asset> cape = Optional.empty();
            ExpectedAppearance installable = expected;
            if (expected.capeTexture().isPresent()) {
                try {
                    cape = Optional.of(resolveRemote(
                            remoteTextures.get(expected.capeTexture().orElseThrow())));
                } catch (IOException | RuntimeException unavailableCape) {


                    installable = new ExpectedAppearance(
                            expected.profileId(),
                            expected.skinTexture(),
                            expected.localSkinSha256(),
                            expected.skinModel(),
                            Optional.empty(),
                            Optional.empty());
                }
            } else if (expected.localCapeCacheKey().isPresent()) {
                String key = expected.localCapeCacheKey().orElseThrow();
                if (textures.readIfCached(key).isPresent()) {
                    cape = Optional.of(new AcknowledgedAppearanceAssets.Asset(
                            key, textures.cachePath(key)));
                } else {
                    installable = new ExpectedAppearance(
                            expected.profileId(),
                            expected.skinTexture(),
                            expected.localSkinSha256(),
                            expected.skinModel(),
                            Optional.empty(),
                            Optional.empty());
                }
            }
            return Optional.of(new ResolvedProfile<>(
                    expected.profileId(),
                    installable,
                    new AcknowledgedAppearanceAssets(skin, cape)));
        } catch (IOException | PngValidationException | RuntimeException unavailableTexture) {
            return Optional.empty();
        }
    }

    private Optional<AcknowledgedAppearanceAssets.Asset> resolveSkin(ExpectedAppearance expected)
            throws IOException, PngValidationException {
        if (expected.localSkinSha256().isPresent()) {
            String hash = expected.localSkinSha256().orElseThrow();
            storage.readAsset(hash);
            return Optional.of(new AcknowledgedAppearanceAssets.Asset(hash, storage.assetPath(hash)));
        }
        if (expected.skinTexture().isPresent()) {
            return Optional.of(resolveRemote(
                    remoteTextures.get(expected.skinTexture().orElseThrow())));
        }
        return Optional.empty();
    }

    private static AcknowledgedAppearanceAssets.Asset resolveRemote(CachedTexture texture) {
        Path path = texture.path();
        String fileName = path.getFileName().toString();
        if (fileName.length() != 68 || !fileName.endsWith(".png")) {
            throw new IllegalStateException("Unexpected texture-cache entry name");
        }
        return new AcknowledgedAppearanceAssets.Asset(fileName.substring(0, 64), path);
    }

    @FunctionalInterface
    interface RemoteTextureSource {
        CachedTexture get(java.net.URI texture) throws IOException;
    }
}
