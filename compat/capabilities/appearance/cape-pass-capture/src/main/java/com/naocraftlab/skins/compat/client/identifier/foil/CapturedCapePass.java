package com.naocraftlab.skins.compat.client.identifier.foil;

import com.naocraftlab.skins.client.TextureUseValidity;
import java.util.Objects;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

public record CapturedCapePass(TextureUseValidity validity, boolean orderedFoil) {
    public CapturedCapePass {
        Objects.requireNonNull(validity, "validity");
    }

    public boolean isValid() {
        return com.naocraftlab.skins.compat.waveycapes.CapeRenderThread.isClientThread() && validity.isValid();
    }

    public static RenderType material(RenderType original, TextureUseValidity validity, boolean orderedFoil) {
        RenderType captured = RenderType.create("nclskins_captured_cape", ((Carrier) original).nclskins$setup());
        ((Carrier) captured).nclskins$setCapture(new CapturedCapePass(validity, orderedFoil));
        return captured;
    }

    public static CapturedCapePass of(RenderType type) {
        return type instanceof Carrier carrier ? carrier.nclskins$capture() : null;
    }

    public interface Carrier {
        RenderSetup nclskins$setup();
        CapturedCapePass nclskins$capture();
        void nclskins$setCapture(CapturedCapePass capture);
    }

    public interface PreparedCarrier {
        CapturedCapePass nclskins$capture();
        void nclskins$setCapture(CapturedCapePass capture);
    }
}
