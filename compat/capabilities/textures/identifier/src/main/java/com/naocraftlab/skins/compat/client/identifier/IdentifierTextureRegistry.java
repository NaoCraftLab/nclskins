package com.naocraftlab.skins.compat.client.identifier;

import com.mojang.blaze3d.platform.NativeImage;
import com.naocraftlab.skins.client.AbstractTextureRegistry;
import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
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
                    minecraft, location, staged.path(), LOCAL_SKIN_SENTINEL + sha256));
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
        private Runnable release;
        private boolean completed;
        private boolean retired;
        private boolean released;

        private NativeTexture(
                OwnedSkinFile stagedFile,
                NativePlayerSkinLifecycle.Registration lifecycle,
                Runnable release,
                boolean completed) {
            this.stagedFile = stagedFile;
            this.lifecycle = lifecycle;
            this.release = release;
            this.completed = completed;
        }

        private static NativeTexture loaded(Runnable release) {
            return new NativeTexture(null, null, Objects.requireNonNull(release, "release"), true);
        }

        private static NativeTexture pending(
                OwnedSkinFile stagedFile,
                NativePlayerSkinLifecycle.Registration lifecycle) {
            return new NativeTexture(
                    Objects.requireNonNull(stagedFile, "stagedFile"),
                    Objects.requireNonNull(lifecycle, "lifecycle"),
                    null,
                    false);
        }

        private void attach(IdentifierNativeTextureBackend.Download download) {
            CompletableFuture<?> registration = Objects.requireNonNull(download.ready(), "registration");
            synchronized (this) {
                if (release != null || completed) {
                    throw new IllegalStateException("Feature texture registration is already attached");
                }
                release = Objects.requireNonNull(download.release(), "release");
            }
            registration.whenComplete((ignored, failure) -> complete(failure));
        }

        private void retire() {
            if (lifecycle != null) {
                lifecycle.retire();
            }
            boolean releaseNow;
            synchronized (this) {
                retired = true;
                releaseNow = completed && !released;
                if (releaseNow) {
                    released = true;
                }
            }
            if (releaseNow) {
                releaseOnClientThread();
            }
        }

        private void complete(Throwable failure) {
            stagedFile.close();
            if (failure == null) {
                lifecycle.ready();
            } else {
                lifecycle.failed();
            }
            boolean releaseNow;
            synchronized (this) {
                completed = true;
                releaseNow = retired && !released;
                if (releaseNow) {
                    released = true;
                }
            }
            if (releaseNow) {
                releaseOnClientThread();
            }
        }

        private void releaseOnClientThread() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.isSameThread()) {
                release.run();
            } else {
                minecraft.execute(release);
            }
        }
    }
}
