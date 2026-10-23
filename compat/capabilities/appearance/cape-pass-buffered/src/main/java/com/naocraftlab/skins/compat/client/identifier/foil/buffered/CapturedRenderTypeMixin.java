package com.naocraftlab.skins.compat.client.identifier.foil.buffered;

import com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderType.class)
public abstract class CapturedRenderTypeMixin implements CapturedCapePass.Carrier {
    @Unique private volatile CapturedCapePass nclskins$capture;
    @Accessor("state") public abstract RenderSetup nclskins$setup();
    @Override public CapturedCapePass nclskins$capture() { return nclskins$capture; }
    @Override public void nclskins$setCapture(CapturedCapePass capture) {
        if (nclskins$capture != null) throw new IllegalStateException("Cape material already captured");
        nclskins$capture = capture;
    }
}
