package com.naocraftlab.skins.compat.mc262.mixin;

import com.naocraftlab.skins.compat.mc262.NclSkinsWideDepthState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AvatarRenderState.class)
abstract class AvatarRenderStateMixin implements NclSkinsWideDepthState {
    @Unique
    private boolean nclskins$wideDepth;

    @Override
    public boolean nclskins$usesWideDepth() {
        return nclskins$wideDepth;
    }

    @Override
    public void nclskins$setWideDepth(boolean wideDepth) {
        nclskins$wideDepth = wideDepth;
    }
}
