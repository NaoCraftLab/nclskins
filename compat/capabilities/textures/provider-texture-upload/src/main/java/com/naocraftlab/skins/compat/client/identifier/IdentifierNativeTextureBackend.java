package com.naocraftlab.skins.compat.client.identifier;

import com.mojang.blaze3d.platform.NativeImage;
import com.naocraftlab.skins.client.NativeTextureRegistration;
import com.naocraftlab.skins.compat.client.identifier.mixin.TextureManagerEntryProviderAccessor;
import com.naocraftlab.skins.compat.client.identifier.mixin.TextureManagerProviderAccessor;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.ExternalTextureProvider;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TextureProvider;
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
        var provider = new ExternalTextureProvider(texture);
        try {
            manager.registerProvider(location, provider);
        } catch (RuntimeException | Error failure) {
            texture.close();
            throw failure;
        }
        return () -> {
            try {
                if (isRegistered(manager, location, provider)) manager.release(location);
            } finally {
                texture.close();
            }
        };
    }

    static boolean isRegistered(TextureManager manager, Identifier location, TextureProvider<?, ?> provider) {
        Object entry = ((TextureManagerProviderAccessor) manager).ncl$getEntries().get(location);
        return provider != null && entry instanceof TextureManagerEntryProviderAccessor access
                && access.ncl$getProvider() == provider;
    }

    static CompletableFuture<?> download(Minecraft minecraft, Identifier location, Path staged,
            String sentinel, NativeTextureRegistration ownership) {
        var downloader = new FeatureSkinTextureDownloader(
                minecraft.getProxy(), minecraft.getTextureManager(), minecraft, location);
        Runnable release = () -> {
            if (minecraft.isSameThread()) downloader.close();
            else minecraft.execute(downloader::close);
        };
        return downloader.downloadAndRegisterSkin(location, staged, sentinel, true).whenComplete((ignored, failure) -> {
            if (failure == null) ownership.acquire(() -> {}, release, release);
            else release.run();
        });
    }
}
