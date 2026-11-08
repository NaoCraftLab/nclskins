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
    @Unique private volatile EffectiveSkinSource nclskins$source;
    @Unique private volatile EffectiveSkinSource nclskins$accessorySource;
    @Unique private AutoCloseable nclskins$retirement;
    @Unique private long nclskins$accessoryBinding;
    @Unique private boolean nclskins$accessoryClosed;
    @Unique private com.naocraftlab.skins.runtime.CpmAccessoryObserver nclskins$accessories;
    @Unique private Object nclskins$modelIdentity;
    @Unique private long nclskins$modelRevision;
    @Unique private ModelDefinition nclskins$contentIdentity;
    @Unique private com.naocraftlab.skins.compat.cpm.CpmAccessoryContent nclskins$content;

    @Override public EffectiveSkinSource nclskins$source() { return nclskins$source; }
    @Override public EffectiveSkinSource nclskins$accessorySource() { return nclskins$accessorySource; }
    @Override public synchronized void nclskins$source(EffectiveSkinSource source) {
        nclskins$source = source;
        nclskins$bindAccessories(source);
    }

    @Unique
    private void nclskins$clearMemo() {
        nclskins$modelIdentity = null;
        nclskins$contentIdentity = null;
        nclskins$content = null;
    }

    @Unique
    private void nclskins$detachAccessories() {
        ++nclskins$accessoryBinding;
        var previous = nclskins$retirement;
        nclskins$retirement = null;
        if (previous != null) {
            try { previous.close(); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        nclskins$clearMemo();
        nclskins$accessories = null;
    }

    @Unique
    private void nclskins$bindAccessories(EffectiveSkinSource source) {
        nclskins$detachAccessories();
        long binding = nclskins$accessoryBinding;
        nclskins$accessoryClosed = false;
        nclskins$accessorySource = source;
        nclskins$accessories = source == null ? null : com.naocraftlab.skins.runtime.CapeProjection.cpmObserver(
                source, ((Player<?>) (Object) this).getName());
        if (source != null) {
            var retirement = source.onRetirement(() -> nclskins$retireAccessories(source, binding));
            if (nclskins$accessorySource == source && source.active()) nclskins$retirement = retirement;
            else {
                try { retirement.close(); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }
        }
    }

    @Unique
    private synchronized void nclskins$retireAccessories(EffectiveSkinSource source, long binding) {
        if (nclskins$accessorySource != source || nclskins$accessoryBinding != binding) return;
        nclskins$accessories = null;
        nclskins$retirement = null;
        nclskins$clearMemo();
    }

    @WrapMethod(method = "getModelDefinition()Lcom/tom/cpm/shared/definition/ModelDefinition;")
    private synchronized ModelDefinition nclskins$accessories(Operation<ModelDefinition> original) {
        ModelDefinition resolved = original.call();
        Player<?> player = (Player<?>) (Object) this;
        if (!nclskins$accessoryClosed && nclskins$accessorySource == null && nclskins$source == null) {
            var current = com.naocraftlab.skins.runtime.CapeProjection.skinSource(player.getUUID());
            if (current != null && current.active() && !current.preview()) {
                nclskins$bindAccessories(current);
            }
        }
        if (nclskins$accessories != null && nclskins$accessorySource.active()) {
            ModelDefinition raw = player.getModelDefinition0();
            Object identity = raw == null ? player.getDefinitionFuture() : raw;
            if (identity != nclskins$modelIdentity) {
                nclskins$modelIdentity = identity;
                nclskins$modelRevision++;
            }
            if (resolved == null || !Player.isEnableRendering() || !resolved.doRender()) {
                nclskins$contentIdentity = null;
                nclskins$content = null;
            } else if (resolved != nclskins$contentIdentity) {
                nclskins$content = com.naocraftlab.skins.compat.cpm.CpmAccessoryContent.observe(resolved);
                nclskins$contentIdentity = resolved;
            }
            if (!nclskins$accessorySource.active()) { nclskins$clearMemo(); return resolved; }
            CpmIntegration.observe(player, resolved, raw, nclskins$accessories, nclskins$modelRevision,
                    nclskins$content == null ? com.naocraftlab.skins.compat.cpm.CpmAccessoryContent.unknown() : nclskins$content);
        } else nclskins$clearMemo();
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
    private synchronized void nclskins$cleanup(Operation<Void> original) {
        nclskins$accessoryClosed = true;
        nclskins$detachAccessories();
        if (nclskins$source == null) { original.call(); return; }
        var future = ((Player<?>) (Object) this).getDefinitionFuture();
        if (future != null) future.whenComplete((definition, failure) -> {
            if (definition != null) MinecraftClientAccess.get().executeOnGameThread(definition::cleanup);
        });
    }
}
