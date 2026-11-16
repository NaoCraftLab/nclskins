package com.naocraftlab.skins.buildlogic.probe;

import com.tom.cpm.shared.animation.*;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.definition.ModelDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=AnimationEngine.class,remap=false)
public abstract class ReferenceNeutralMixin {
    @Inject(method="handleAnimation(Lcom/tom/cpm/shared/animation/AnimationState;Lcom/tom/cpm/shared/config/Player;Lcom/tom/cpm/shared/definition/ModelDefinition;)V",at=@At("HEAD"),cancellable=true,require=1,expect=1,allow=1)
    private void reference$authoredNeutral(AnimationState state,Player<?> player,ModelDefinition definition,CallbackInfo ci){
        if(ReferenceNeutral.matches(player,definition)){definition.resetAnimationPos();ReferenceNeutral.calls++;ci.cancel();}
    }
}
