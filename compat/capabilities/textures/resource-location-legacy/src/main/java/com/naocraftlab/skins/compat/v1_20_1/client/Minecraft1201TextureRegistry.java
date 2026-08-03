package com.naocraftlab.skins.compat.v1_20_1.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.naocraftlab.skins.client.AbstractTextureRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;


public final class Minecraft1201TextureRegistry extends AbstractTextureRegistry<ResourceLocation> {
    private static final Pattern PATH_ROOT = Pattern.compile("[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*");

    private final String pathRoot;

    public Minecraft1201TextureRegistry() {
        this("preview");
    }

    Minecraft1201TextureRegistry(String pathRoot) {
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
    protected LoadedTexture<ResourceLocation> load(
            TextureKind kind, String sha256, byte[] pngBytes) throws IOException {
        NativeImage image;
        try (ByteArrayInputStream input = new ByteArrayInputStream(pngBytes)) {
            image = NativeImage.read(input);
        }
        String kindPath = kind == TextureKind.PLAYER_SKIN ? "skin" : "image";
        DynamicTexture texture = new DynamicTexture(image);
        ResourceLocation location;
        try {
            location = Minecraft.getInstance().getTextureManager().register(
                    "nclskins/" + pathRoot + '/' + kindPath + '/' + sha256, texture);
        } catch (RuntimeException failure) {
            texture.close();
            throw failure;
        }
        return new LoadedTexture<>(
                new TextureHandle(location.toString(), image.getWidth(), image.getHeight()),
                location);
    }

    @Override
    protected void unload(ResourceLocation location) {
        Minecraft.getInstance().getTextureManager().release(location);
    }

    @Override
    protected void checkClientThread() {
        if (!Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("Dynamic textures must be changed on the Minecraft client thread");
        }
    }

    @Override
    public String toString() {
        return "Minecraft1201TextureRegistry[closed=" + isClosed()
                + ", registered=" + liveTextureCount() + ']';
    }
}
