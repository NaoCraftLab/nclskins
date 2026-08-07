package com.naocraftlab.skins.mc1211;

import com.mojang.blaze3d.platform.NativeImage;
import com.naocraftlab.skins.client.AbstractTextureRegistry;
import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import com.naocraftlab.skins.client.NativeTextureUploadTracker;
import com.naocraftlab.skins.client.OwnedSkinFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.HttpTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;


public final class MinecraftTextureRegistry
        extends AbstractTextureRegistry<MinecraftTextureRegistry.NativeTexture> {
    private static final Pattern PATH_ROOT = Pattern.compile("[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*");
    private static final String LOCAL_SKIN_SENTINEL = "nclskins-local://feature-skin/";
    private static final UUID FALLBACK_PROFILE_ID = new UUID(0L, 0L);

    private final String pathRoot;

    public MinecraftTextureRegistry() {
        this("preview");
    }

    MinecraftTextureRegistry(String pathRoot) {
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
    protected LoadedTexture<NativeTexture> load(
            TextureKind kind, String sha256, byte[] pngBytes) throws IOException {
        if (kind == TextureKind.PLAYER_SKIN) {
            return loadFeatureSkin(sha256, pngBytes);
        }
        NativeImage image;
        try (ByteArrayInputStream input = new ByteArrayInputStream(pngBytes)) {
            image = NativeImage.read(input);
        }
        String kindPath = switch (kind) {
            case PLAYER_SKIN -> throw new IllegalStateException(
                    "Player skins use the native player-skin pipeline");
            case IMAGE -> "image";
        };
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                "nclskins", pathRoot + '/' + kindPath + '/' + sha256);
        DynamicTexture texture = new DynamicTexture(image);
        try {
            Minecraft.getInstance().getTextureManager().register(location, texture);
        } catch (RuntimeException failure) {
            texture.close();
            throw failure;
        }
        return new LoadedTexture<>(
                new TextureHandle(location.toString(), image.getWidth(), image.getHeight()),
                new NativeTexture(location, texture, null, null));
    }

    @Override
    protected void unload(NativeTexture resource) {
        if (resource.registration() != null) {
            resource.registration().retire();
            NativeTextureUploadTracker.forget(resource.texture());
        }
        Minecraft.getInstance().getTextureManager().release(resource.location());
        resource.closeStagedFile();
    }

    @Override
    protected void checkClientThread() {
        if (!Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("Dynamic textures must be changed on the Minecraft client thread");
        }
    }

    private LoadedTexture<NativeTexture> loadFeatureSkin(String sha256, byte[] pngBytes)
            throws IOException {
        OwnedSkinFile staged = OwnedSkinFile.stage(sha256, pngBytes);
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                "nclskins", pathRoot + "/feature_skin/" + sha256);
        NativePlayerSkinLifecycle.Registration registration =
                NativePlayerSkinLifecycle.pending(location.toString());
        HttpTexture texture = new HttpTexture(
                staged.path().toFile(),
                LOCAL_SKIN_SENTINEL + sha256,
                DefaultPlayerSkin.get(FALLBACK_PROFILE_ID).texture(),
                true,
                () -> { });
        NativeTextureUploadTracker.track(texture, registration);
        try {
            Minecraft.getInstance().getTextureManager().register(location, texture);
        } catch (RuntimeException failure) {
            texture.close();
            registration.failed();
            NativeTextureUploadTracker.forget(texture);
            registration.retire();
            staged.close();
            throw failure;
        }
        return new LoadedTexture<>(
                new TextureHandle(location.toString(), 64, 64),
                new NativeTexture(location, texture, staged, registration));
    }

    protected record NativeTexture(
            ResourceLocation location,
            Object texture,
            OwnedSkinFile stagedFile,
            NativePlayerSkinLifecycle.Registration registration) {
        protected NativeTexture {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(texture, "texture");
        }

        private void closeStagedFile() {
            if (stagedFile != null) {
                stagedFile.close();
            }
        }
    }
}
