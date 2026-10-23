package com.naocraftlab.skins.compat.client.identifier.foil.prepared;

import com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(PreparedRenderType.class)
public abstract class CapturedPreparedTypeMixin implements CapturedCapePass.PreparedCarrier {
    @Unique private volatile CapturedCapePass nclskins$capture;

    @Override public CapturedCapePass nclskins$capture() { return nclskins$capture; }
    @Override public void nclskins$setCapture(CapturedCapePass capture) {
        if (nclskins$capture != null) throw new IllegalStateException("Prepared cape already captured");
        nclskins$capture = capture;
    }
}
