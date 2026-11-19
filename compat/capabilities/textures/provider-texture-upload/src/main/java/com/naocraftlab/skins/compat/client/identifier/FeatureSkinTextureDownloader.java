package com.naocraftlab.skins.compat.client.identifier;

import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import java.net.Proxy;
import java.util.concurrent.Executor;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

public final class FeatureSkinTextureDownloader extends SkinTextureDownloader {
    private final NativePlayerSkinLifecycle.Registration registration;

    FeatureSkinTextureDownloader(Proxy proxy, TextureManager manager, Executor clientExecutor, Identifier location) {
        super(proxy, manager, clientExecutor);
        registration = NativePlayerSkinLifecycle.registration(location.toString()).orElseThrow();
    }

    public boolean mayPublish(Identifier location) {
        String path = location.toString();
        if (!registration.location().equals(path)
                || NativePlayerSkinLifecycle.registration(path).orElse(null) != registration) return false;
        return switch (registration.state()) {
            case PENDING, READY -> true;
            case FAILED, RETIRED -> false;
        };
    }
}
