package com.naocraftlab.skins.compat.client.identifier.mixin;

import com.naocraftlab.skins.compat.client.identifier.CapeMaterialSnapshot;
import com.naocraftlab.skins.client.ExactLocalPlayerScope;
import com.naocraftlab.skins.runtime.CapeProjection;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMaterialMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("RETURN"), require = 1, expect = 1, allow = 1)
    private void nclskins$captureCapeMaterial(Avatar avatar, AvatarRenderState state,
            float partialTick, CallbackInfo callback) {
        CapeMaterialSnapshot snapshot = null;
        if (state.skin != null && state.skin.cape() != null) {
            String installed = state.skin.cape().texturePath().toString();
            Boolean previewGlint = ExactLocalPlayerScope.capeGlint(avatar, installed);
            if (previewGlint != null) {
                if (previewGlint) snapshot = new CapeMaterialSnapshot(installed, true,
                        ExactLocalPlayerScope.capeValidity(avatar, installed));
            } else {
                String name = avatar.getScoreboardName();
                if (name == null) {
                    ((CapeMaterialSnapshot.Carrier) state).nclskins$setCapeMaterial(null);
                    return;
                }
                CapeProjection.Result selected = CapeProjection.resolveSelf(avatar.getUUID(), name)
                        .orElseGet(() -> CapeProjection.material(avatar.getUUID(), name, false));
                if (selected.capeGlint() && installed.equals(selected.capeLocation())) {
                    snapshot = CapeMaterialSnapshot.world(avatar.getUUID(), name, avatar.getUUID().equals(
                            net.minecraft.client.Minecraft.getInstance().getUser().getProfileId()), installed);
                }
            }
        }
        ((CapeMaterialSnapshot.Carrier) state).nclskins$setCapeMaterial(snapshot);
    }
}
