package com.naocraftlab.skins.compat.client.identifier;

import com.mojang.blaze3d.platform.NativeImage;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.ExternalTextureProvider;
import net.minecraft.client.renderer.texture.TextureResources;
import net.minecraft.resources.Identifier;

final class IdentifierNativeTextureBackend {
    private static final AtomicLong FEATURE_INCARNATION = new AtomicLong();

    private IdentifierNativeTextureBackend() {
    }

    static String featureLocationPath(String contentPath) {
        return contentPath + '/' + Long.toUnsignedString(FEATURE_INCARNATION.getAndIncrement());
    }

    static Runnable upload(Minecraft minecraft, Identifier location, String name, NativeImage image) {
        TextureResources texture;
        var manager = minecraft.getTextureManager();
        try (image) {
            texture = TextureResources.from2dImage(() -> name, image);
        }
        try {
            manager.registerProvider(location, new ExternalTextureProvider(texture));
        } catch (RuntimeException | Error failure) {
            texture.close();
            throw failure;
        }
        return () -> {
            try {
                manager.release(location);
            } finally {
                texture.close();
            }
        };
    }

    static Download download(Minecraft minecraft, Identifier location, Path staged, String sentinel) {
        var downloader = new FeatureSkinTextureDownloader(
                minecraft.getProxy(), minecraft.getTextureManager(), minecraft, location);
        return new Download(downloader.downloadAndRegisterSkin(location, staged, sentinel, true), downloader::close);
    }

    record Download(CompletableFuture<?> ready, Runnable release) {
    }
}
