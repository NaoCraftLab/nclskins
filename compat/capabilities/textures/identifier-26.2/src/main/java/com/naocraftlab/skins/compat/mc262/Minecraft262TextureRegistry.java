package com.naocraftlab.skins.compat.mc262;

import com.mojang.blaze3d.platform.NativeImage;
import com.naocraftlab.skins.client.AbstractTextureRegistry;
import com.naocraftlab.skins.client.OwnedSkinFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.resources.Identifier;


public final class Minecraft262TextureRegistry
        extends AbstractTextureRegistry<Minecraft262TextureRegistry.NativeTexture> {
    private static final Pattern PATH_ROOT = Pattern.compile("[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*");
    private static final String LOCAL_SKIN_SENTINEL = "nclskins-local://feature-skin/";

    private final String pathRoot;

    public Minecraft262TextureRegistry() {
        this("dynamic");
    }


    Minecraft262TextureRegistry(String pathRoot) {
        this.pathRoot = Objects.requireNonNull(pathRoot, "pathRoot");
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
        if (kind == TextureKind.PLAYER_SKIN_FEATURE_PRESERVING) {
            return loadFeatureSkin(sha256, pngBytes);
        }
        NativeImage image;
        try (ByteArrayInputStream input = new ByteArrayInputStream(pngBytes)) {
            image = NativeImage.read(input);
        }
        String kindPath = switch (kind) {
            case PLAYER_SKIN -> "skin";
            case PLAYER_SKIN_FEATURE_PRESERVING -> throw new IllegalStateException(
                    "Feature skins use the native player-skin pipeline");
            case IMAGE -> "image";
        };
        Identifier location = Identifier.fromNamespaceAndPath(
                "nclskins", pathRoot + '/' + kindPath + '/' + sha256);
        DynamicTexture texture = new DynamicTexture(
                () -> "NCL Skins " + sha256.substring(0, 12), image);
        try {
            Minecraft.getInstance().getTextureManager().register(location, texture);
        } catch (RuntimeException failure) {
            texture.close();
            throw failure;
        }
        return new LoadedTexture<>(
                new TextureHandle(location.toString(), image.getWidth(), image.getHeight()),
                NativeTexture.loaded(location));
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
                "nclskins", pathRoot + "/feature_skin/" + sha256);
        OwnedSkinFile staged = OwnedSkinFile.stage(sha256, pngBytes);
        NativeTexture resource = NativeTexture.pending(location, staged);
        try {
            CompletableFuture<?> registration = new SkinTextureDownloader(
                    minecraft.getProxy(), minecraft.getTextureManager(), minecraft)
                    .downloadAndRegisterSkin(
                            location,
                            staged.path(),
                            LOCAL_SKIN_SENTINEL + sha256,
                            true);
            resource.attach(registration);
        } catch (RuntimeException failure) {
            staged.close();
            throw failure;
        }
        return new LoadedTexture<>(
                new TextureHandle(location.toString(), 64, 64), resource);
    }

    protected static final class NativeTexture {
        private final Identifier location;
        private final OwnedSkinFile stagedFile;
        private CompletableFuture<?> registration;
        private boolean completed;
        private boolean retired;
        private boolean released;

        private NativeTexture(
                Identifier location, OwnedSkinFile stagedFile, boolean completed) {
            this.location = Objects.requireNonNull(location, "location");
            this.stagedFile = stagedFile;
            this.completed = completed;
        }

        private static NativeTexture loaded(Identifier location) {
            return new NativeTexture(location, null, true);
        }

        private static NativeTexture pending(Identifier location, OwnedSkinFile stagedFile) {
            return new NativeTexture(
                    location, Objects.requireNonNull(stagedFile, "stagedFile"), false);
        }

        private void attach(CompletableFuture<?> registration) {
            synchronized (this) {
                if (this.registration != null || completed) {
                    throw new IllegalStateException("Feature texture registration is already attached");
                }
                this.registration = Objects.requireNonNull(registration, "registration");
            }
            registration.whenComplete((ignored, failure) -> complete());
        }

        private void retire() {
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

        private void complete() {
            stagedFile.close();
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
            Runnable release = () -> minecraft.getTextureManager().release(location);
            if (minecraft.isSameThread()) {
                release.run();
            } else {
                minecraft.execute(release);
            }
        }
    }
}
