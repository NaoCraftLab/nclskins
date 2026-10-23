package com.naocraftlab.skins.compat.cpm.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.naocraftlab.skins.compat.cpm.CpmPlayerAccess;
import com.naocraftlab.skins.compat.cpm.CpmIntegration;
import com.naocraftlab.skins.compat.cpm.CpmTextureLoader;
import com.naocraftlab.skins.client.SkinModel;
import com.tom.cpm.shared.model.SkinType;
import com.tom.cpm.shared.skin.PlayerTextureLoader;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "com.tom.cpm.client.PlayerProfile", remap = false)
abstract class PlayerProfileMixin {
    @ModifyReturnValue(method = "initTextures()Lcom/tom/cpm/shared/skin/PlayerTextureLoader;", at = @At("RETURN"), require = 1, expect = 1, allow = 1)
    private PlayerTextureLoader nclskins$textures(PlayerTextureLoader original) {
        var source = ((CpmPlayerAccess) this).nclskins$source();
        return source == null ? original : new CpmTextureLoader(source, original);
    }

    @WrapMethod(method = "getSkinType()Lcom/tom/cpm/shared/model/SkinType;")
    private SkinType nclskins$variant(Operation<SkinType> original) {
        var source = ((CpmPlayerAccess) this).nclskins$source();
        return source == null ? original.call() : source.model() == SkinModel.SLIM ? SkinType.SLIM : SkinType.DEFAULT;
    }

    @ModifyReturnValue(method = "getUUID()Ljava/util/UUID;", at = @At("RETURN"), require = 1, expect = 1, allow = 1)
    private UUID nclskins$subject(UUID original) {
        return CpmIntegration.subject((com.tom.cpm.shared.config.Player<?>) (Object) this, original);
    }
}
