package com.naocraftlab.skins.compat.client.cape;

import com.naocraftlab.skins.client.TextureUseValidity;
import java.util.Optional;
import net.minecraft.client.renderer.RenderType;

public abstract class CapturedCapePass extends RenderType {
    protected final RenderType original;
    protected final TextureUseValidity validity;

    protected CapturedCapePass(RenderType original, TextureUseValidity validity) {
        super("nclskins_captured_cape", original.format(), original.mode(), original.bufferSize(),
                original.affectsCrumbling(), ((RenderTypeFlags) original).nclskins$sortOnUpload(),
                original::setupRenderState, original::clearRenderState);
        this.original = original;
        this.validity = java.util.Objects.requireNonNull(validity, "validity");
    }

    protected final boolean isValid() {
        return com.naocraftlab.skins.compat.waveycapes.CapeRenderThread.isClientThread() && validity.isValid();
    }

    @Override public Optional<RenderType> outline() { return original.outline(); }
    @Override public boolean isOutline() { return original.isOutline(); }

    public interface RenderTypeFlags {
        boolean nclskins$sortOnUpload();
    }
}
