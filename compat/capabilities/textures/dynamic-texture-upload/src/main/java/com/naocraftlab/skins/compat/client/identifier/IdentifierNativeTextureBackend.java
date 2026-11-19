package com.naocraftlab.skins.compat.client.identifier;

import com.mojang.blaze3d.platform.NativeImage;
import com.naocraftlab.skins.client.NativeTextureRegistration;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.resources.Identifier;

final class IdentifierNativeTextureBackend {
    private static final AtomicLong FEATURE_INCARNATION = new AtomicLong();

    private IdentifierNativeTextureBackend() {
    }

    static String featureLocationPath(String contentPath) {
        return contentPath + '/' + Long.toUnsignedString(FEATURE_INCARNATION.getAndIncrement());
    }

    static Runnable upload(Minecraft minecraft, Identifier location, String name, NativeImage image) {
        var manager = minecraft.getTextureManager();
        DynamicTexture texture = null;
        try {
            texture = new DynamicTexture(() -> name, image);
            manager.register(location, texture);
        } catch (RuntimeException | Error failure) {
            if (texture == null) image.close();
            else texture.close();
            throw failure;
        }
        DynamicTexture registered = texture;
        return () -> {
            if (((RegisteredTextureAccess) manager).nclskins$isRegistered(location, registered)) {
                manager.release(location);
            }
        };
    }

    static CompletableFuture<?> download(Minecraft minecraft, Identifier location, Path staged,
            String sentinel, NativeTextureRegistration ownership) {
        var manager = minecraft.getTextureManager();
        var downloader = new SkinTextureDownloader(minecraft.getProxy(), manager, minecraft);
        ((SkinTextureRegistrationOwner) downloader).nclskins$textureRegistration(ownership);
        return downloader.downloadAndRegisterSkin(location, staged, sentinel, true);
    }
}
