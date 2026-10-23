package com.naocraftlab.skins.compat.cpm.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.naocraftlab.skins.compat.cpm.OwnedCpmDefinition;
import com.tom.cpm.shared.animation.AnimationEngine;
import com.tom.cpm.shared.animation.AnimationState;
import com.tom.cpm.shared.animation.PersistentAnimationState;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.definition.ModelDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "com.tom.cpm.shared.animation.AnimationEngine", remap = false)
abstract class AnimationEngineMixin {
    @WrapOperation(method = "prepareAnimations(Lcom/tom/cpm/shared/animation/PersistentAnimationState;Lcom/tom/cpm/shared/animation/AnimationState;Lcom/tom/cpm/shared/definition/ModelDefinition;)V",
            at = @At(value = "INVOKE", target = "Lcom/tom/cpm/shared/animation/AnimationEngine;getTime()J"), require = 1, expect = 1, allow = 1)
    private long nclskins$prepareTime(AnimationEngine engine, Operation<Long> original,
            PersistentAnimationState persistent, AnimationState state, ModelDefinition definition) {
        long time = original.call(engine);
        return definition instanceof OwnedCpmDefinition owned ? owned.animationTime(time) : time;
    }

    @WrapOperation(method = "handleAnimation(Lcom/tom/cpm/shared/animation/AnimationState;Lcom/tom/cpm/shared/config/Player;Lcom/tom/cpm/shared/definition/ModelDefinition;)V",
            at = @At(value = "INVOKE", target = "Lcom/tom/cpm/shared/animation/AnimationEngine;getTime()J"), require = 1, expect = 1, allow = 1)
    private long nclskins$animationTime(AnimationEngine engine, Operation<Long> original,
            AnimationState state, Player<?> player, ModelDefinition definition) {
        long time = original.call(engine);
        return definition instanceof OwnedCpmDefinition owned ? owned.animationTime(time) : time;
    }
}
