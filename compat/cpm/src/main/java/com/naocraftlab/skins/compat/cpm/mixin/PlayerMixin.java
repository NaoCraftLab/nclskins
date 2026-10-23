package com.naocraftlab.skins.compat.cpm.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.naocraftlab.skins.compat.cpm.CpmPlayerAccess;
import com.naocraftlab.skins.compat.cpm.CpmIntegration;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.naocraftlab.skins.runtime.EffectiveSkinSource;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.definition.ModelDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

@Pseudo
@Mixin(targets = "com.tom.cpm.shared.config.Player", remap = false)
abstract class PlayerMixin implements CpmPlayerAccess {
    @Unique private EffectiveSkinSource nclskins$source;
    @Unique private EffectiveSkinSource nclskins$accessorySource;
    @Unique private com.naocraftlab.skins.runtime.CpmAccessoryObserver nclskins$accessories;
    @Unique private Object nclskins$modelIdentity;
    @Unique private long nclskins$modelRevision;

    @Override public EffectiveSkinSource nclskins$source() { return nclskins$source; }
    @Override public EffectiveSkinSource nclskins$accessorySource() { return nclskins$accessorySource; }
    @Override public void nclskins$source(EffectiveSkinSource source) {
        nclskins$source = source;
        nclskins$accessorySource = source;
        nclskins$accessories = source == null ? null : com.naocraftlab.skins.runtime.CapeProjection.cpmObserver(
                source, ((Player<?>) (Object) this).getName());
    }

    @WrapMethod(method = "getModelDefinition()Lcom/tom/cpm/shared/definition/ModelDefinition;")
    private ModelDefinition nclskins$accessories(Operation<ModelDefinition> original) {
        ModelDefinition resolved = original.call();
        Player<?> player = (Player<?>) (Object) this;
        if (nclskins$accessorySource == null) {
            var current = com.naocraftlab.skins.runtime.CapeProjection.skinSource(player.getUUID());
            if (current != null && current.active() && !current.preview()) {
                nclskins$accessorySource = current;
                nclskins$accessories = com.naocraftlab.skins.runtime.CapeProjection.cpmObserver(current, player.getName());
            }
        }
        if (nclskins$accessories != null) {
            ModelDefinition raw = player.getModelDefinition0();
            Object identity = raw == null ? player.getDefinitionFuture() : raw;
            if (identity != nclskins$modelIdentity) {
                nclskins$modelIdentity = identity;
                nclskins$modelRevision++;
            }
            CpmIntegration.observe(player, resolved, raw, nclskins$accessories, nclskins$modelRevision);
        }
        return resolved;
    }

    @Inject(method = "setEnableRendering(Z)V", at = @At("TAIL"), require = 1, expect = 1, allow = 1)
    private static void nclskins$configurationChanged(boolean enabled, CallbackInfo ci) { CpmIntegration.settingsChanged(); }

    @WrapMethod(method = "isClientPlayer()Z")
    private boolean nclskins$privilege(Operation<Boolean> original) {
        return nclskins$source != null && nclskins$source.preview() ? false : original.call();
    }

    @WrapMethod(method = "getModelDefinition0()Lcom/tom/cpm/shared/definition/ModelDefinition;")
    private ModelDefinition nclskins$publication(Operation<ModelDefinition> original) {
        return nclskins$source != null && !nclskins$source.active() ? null : original.call();
    }

    @WrapMethod(method = "sendEventSubs()V")
    private void nclskins$events(Operation<Void> original) {
        if (nclskins$source == null || !nclskins$source.preview() && nclskins$source.active()) original.call();
    }

    @WrapMethod(method = "cleanup()V")
    private void nclskins$cleanup(Operation<Void> original) {
        if (nclskins$source == null) { original.call(); return; }
        var future = ((Player<?>) (Object) this).getDefinitionFuture();
        if (future != null) future.whenComplete((definition, failure) -> {
            if (definition != null) MinecraftClientAccess.get().executeOnGameThread(definition::cleanup);
        });
    }
}
