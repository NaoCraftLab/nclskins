package com.naocraftlab.skins.compat.cpm;

import com.naocraftlab.skins.runtime.EffectiveSkinSource;
import com.tom.cpl.util.Image;
import com.tom.cpm.shared.skin.PlayerTextureLoader;
import com.tom.cpm.shared.skin.TextureType;
import java.util.concurrent.CompletableFuture;

public final class CpmTextureLoader extends PlayerTextureLoader {
    private final PlayerTextureLoader auxiliary;
    private final CompletableFuture<Image> skin;
    private final EffectiveSkinSource source;

    public CpmTextureLoader(EffectiveSkinSource source, PlayerTextureLoader auxiliary) {
        this.source = source;
        this.auxiliary = auxiliary;
        skin = CpmIntegration.pixels(source);
    }

    @Override protected CompletableFuture<Void> load0() { return skin.thenApply(image -> null); }
    @Override public CompletableFuture<Image> getTexture(TextureType type) {
        return type == TextureType.SKIN ? skin.thenApply(image -> source.active() && image != null ? new Image(image) : null) : auxiliary.load().thenCompose(ignored -> auxiliary.getTexture(type));
    }
    @Override public boolean hasTexture(TextureType type) {
        return type == TextureType.SKIN ? skin.getNow(null) != null : auxiliary.hasTexture(type);
    }
}
