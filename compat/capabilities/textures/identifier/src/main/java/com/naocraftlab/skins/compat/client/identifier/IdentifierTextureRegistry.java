package com.naocraftlab.skins.compat.client.identifier;

import com.mojang.blaze3d.platform.NativeImage;
import com.naocraftlab.skins.client.AbstractTextureRegistry;
import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import com.naocraftlab.skins.client.NativeTextureRegistration;
import com.naocraftlab.skins.client.OwnedSkinFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;


public final class IdentifierTextureRegistry
        extends AbstractTextureRegistry<IdentifierTextureRegistry.NativeTexture> {
    private static final Pattern PATH_ROOT = Pattern.compile("[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*");
    private static final String LOCAL_SKIN_SENTINEL = "nclskins-local://feature-skin/";

    private final String pathRoot;
    private final Runnable lifecycleTransition;

    public IdentifierTextureRegistry() {
        this("dynamic", () -> { });
    }


    IdentifierTextureRegistry(String pathRoot) {
        this(pathRoot, () -> { });
    }

    IdentifierTextureRegistry(String pathRoot, Runnable lifecycleTransition) {
        this.pathRoot = Objects.requireNonNull(pathRoot, "pathRoot");
        this.lifecycleTransition = Objects.requireNonNull(
                lifecycleTransition, "lifecycleTransition");
        if (!PATH_ROOT.matcher(pathRoot).matches()) {
            throw new IllegalArgumentException("Invalid dynamic texture path root");
        }
    }

    public TextureHandle registerSkin(String sha256, Path pngFile) throws IOException {
        return register(TextureKind.PLAYER_SKIN, sha256, pngFile);
    }

    public TextureHandle registerSkin(String sha256, byte[] pngBytes) throws IOException {
        return register(TextureKind.PLAYER_SKIN, sha256, pngBytes);
    }

    @Override
    protected LoadedTexture<NativeTexture> load(TextureKind kind, String sha256, byte[] pngBytes)
            throws IOException {
        if (kind == TextureKind.PLAYER_SKIN) {
            return loadFeatureSkin(sha256, pngBytes);
        }
        NativeImage image;
        try (ByteArrayInputStream input = new ByteArrayInputStream(pngBytes)) {
            image = NativeImage.read(input);
        }
        return registerImage(sha256, image);
    }

    @Override
    protected LoadedTexture<NativeTexture> loadRgba(String sha256, int width, int height,
            byte[] rgba8) throws IOException {
        NativeImage image = new NativeImage(width, height, false);
        try {
            int offset = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int abgr = (rgba8[offset] & 255) | ((rgba8[offset + 1] & 255) << 8)
                            | ((rgba8[offset + 2] & 255) << 16) | ((rgba8[offset + 3] & 255) << 24);
                    image.setPixelABGR(x, y, abgr);
                    offset += 4;
                }
            }
        } catch (RuntimeException | Error failure) {
            image.close();
            throw failure;
        }
        return registerImage(sha256, image);
    }

    private LoadedTexture<NativeTexture> registerImage(String sha256, NativeImage image) {
        Identifier location;
        TextureHandle handle;
        Minecraft minecraft;
        String name;
        try {
            location = Identifier.fromNamespaceAndPath("nclskins", pathRoot + "/image/" + sha256);
            handle = new TextureHandle(location.toString(), image.getWidth(), image.getHeight());
            minecraft = Minecraft.getInstance();
            name = "NCL Skins " + sha256.substring(0, 12);
        } catch (RuntimeException | Error failure) {
            image.close();
            throw failure;
        }
        Runnable release = IdentifierNativeTextureBackend.upload(minecraft, location, name, image);
        return new LoadedTexture<>(handle, NativeTexture.loaded(release));
    }

    @Override
    protected void unload(NativeTexture resource) {
        resource.retire();
    }

    @Override
    protected void checkClientThread() {
        if (!Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("Dynamic textures must be changed on the Minecraft client thread");
        }
    }

    private LoadedTexture<NativeTexture> loadFeatureSkin(String sha256, byte[] pngBytes)
            throws IOException {
        Minecraft minecraft = Minecraft.getInstance();
        Identifier location = Identifier.fromNamespaceAndPath(
                "nclskins", IdentifierNativeTextureBackend.featureLocationPath(pathRoot + "/feature_skin/" + sha256));
        OwnedSkinFile staged = OwnedSkinFile.stage(sha256, pngBytes);
        NativePlayerSkinLifecycle.Registration lifecycle =
                NativePlayerSkinLifecycle.pending(location.toString(), lifecycleTransition);
        NativeTexture resource = NativeTexture.pending(staged, lifecycle);
        try {
            resource.attach(IdentifierNativeTextureBackend.download(
                    minecraft, location, staged.path(), LOCAL_SKIN_SENTINEL + sha256, resource.ownership));
        } catch (RuntimeException failure) {
            lifecycle.failed();
            lifecycle.retire();
            staged.close();
            throw failure;
        }
        return new LoadedTexture<>(
                new TextureHandle(location.toString(), 64, 64), resource);
    }

    protected static final class NativeTexture {
        private final OwnedSkinFile stagedFile;
        private final NativePlayerSkinLifecycle.Registration lifecycle;
        private final NativeTextureRegistration ownership;

        private NativeTexture(OwnedSkinFile stagedFile,
                NativePlayerSkinLifecycle.Registration lifecycle) {
            this.stagedFile = stagedFile;
            this.lifecycle = lifecycle;
            this.ownership = new NativeTextureRegistration(() -> lifecycle == null
                    || (lifecycle.state() != NativePlayerSkinLifecycle.State.FAILED
                    && NativePlayerSkinLifecycle.registration(lifecycle.location()).orElse(null) == lifecycle));
        }

        private static NativeTexture loaded(Runnable release) {
            NativeTexture resource = new NativeTexture(null, null);
            resource.ownership.acquire(() -> {}, release, release);
            return resource;
        }

        private static NativeTexture pending(OwnedSkinFile stagedFile,
                NativePlayerSkinLifecycle.Registration lifecycle) {
            return new NativeTexture(Objects.requireNonNull(stagedFile, "stagedFile"),
                    Objects.requireNonNull(lifecycle, "lifecycle"));
        }

        private void attach(CompletableFuture<?> registration) {
            Objects.requireNonNull(registration, "registration").whenComplete((ignored, failure) -> {
                ownership.complete();
                stagedFile.close();
                if (failure == null) lifecycle.ready();
                else lifecycle.failed();
            });
        }

        private void retire() {
            try {
                ownership.retire();
            } finally {
                if (lifecycle != null) lifecycle.retire();
            }
        }
    }
}
