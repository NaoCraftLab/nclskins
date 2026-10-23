package com.naocraftlab.skins.compat.client.identifier.mixin;

import com.naocraftlab.skins.compat.client.identifier.CapeMaterialSnapshot;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AvatarRenderState.class)
public abstract class AvatarRenderStateMaterialMixin implements CapeMaterialSnapshot.Carrier {
    @Unique private CapeMaterialSnapshot nclskins$capeMaterial;

    @Override public CapeMaterialSnapshot nclskins$capeMaterial() { return nclskins$capeMaterial; }
    @Override public void nclskins$setCapeMaterial(CapeMaterialSnapshot snapshot) {
        nclskins$capeMaterial = snapshot;
    }
}
