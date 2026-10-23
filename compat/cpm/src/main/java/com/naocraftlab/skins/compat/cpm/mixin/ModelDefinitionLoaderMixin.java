package com.naocraftlab.skins.compat.cpm.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.naocraftlab.skins.compat.cpm.CpmIntegration;
import com.naocraftlab.skins.compat.cpm.CpmPlayerAccess;
import com.naocraftlab.skins.compat.cpm.CpmModelCacheAccess;
import com.naocraftlab.skins.compat.cpm.OwnedCpmDefinition;
import com.naocraftlab.skins.runtime.CapeProjection;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.definition.ModelDefinitionLoader;
import com.tom.cpm.shared.network.NetHandler;
import java.io.InputStream;
import java.util.UUID;
import java.util.function.Function;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "com.tom.cpm.shared.definition.ModelDefinitionLoader", remap = false)
abstract class ModelDefinitionLoaderMixin implements CpmModelCacheAccess {
    @Shadow @Final private com.google.common.cache.LoadingCache<Object, Player<?>> cache;
    @Unique private static final ThreadLocal<OwnedCpmDefinition> nclskins$decoding = new ThreadLocal<>();

    @Override public void nclskins$refresh(UUID cacheId, com.naocraftlab.skins.runtime.EffectiveSkinSource current) {
        cache.asMap().forEach((key, player) -> {
            var source = CpmIntegration.source(player);
            if ((source == null ? player.getUUID().equals(cacheId) : source.cacheId().equals(cacheId))
                    && source != current) cache.asMap().remove(key, player);
        });
    }

    @WrapMethod(method = "loadModel(Ljava/io/InputStream;Lcom/tom/cpm/shared/config/Player;)Lcom/tom/cpm/shared/definition/ModelDefinition;")
    private ModelDefinition nclskins$decode(InputStream input, Player<?> player, Operation<ModelDefinition> original) {
        var source = CpmIntegration.source(player);
        if (source == null) return original.call(input, player);
        if (!source.active()) return null;
        OwnedCpmDefinition previous = nclskins$decoding.get();
        nclskins$decoding.remove();
        ModelDefinition result = null;
        try {
            result = original.call(input, player);
            return result;
        } finally {
            OwnedCpmDefinition decoded = nclskins$decoding.get();
            if (previous == null) nclskins$decoding.remove(); else nclskins$decoding.set(previous);
            if (decoded != null) {
                if (decoded != result) decoded.cleanup();
                decoded.decoded();
            }
        }
    }

    @Inject(method = "clearCache()V", at = @At("TAIL"), require = 1, expect = 1, allow = 1)
    private void nclskins$configurationChanged(CallbackInfo ci) { CpmIntegration.settingsChanged(); }

    @WrapOperation(method = "loadPlayer(Lcom/tom/cpm/shared/definition/ModelDefinitionLoader$Key;)Lcom/tom/cpm/shared/config/Player;",
            at = @At(value = "INVOKE", target = "Ljava/util/function/Function;apply(Ljava/lang/Object;)Ljava/lang/Object;"), require = 1, expect = 1, allow = 1)
    private Object nclskins$tag(Function<?, ?> factory, Object profile, Operation<Object> original) {
        Player<?> player = (Player<?>) original.call(factory, profile);
        var source = CapeProjection.findSkinSource(player.getUUID());
        if (source != null) ((CpmPlayerAccess) player).nclskins$source(source);
        return player;
    }

    @WrapOperation(method = "loadModel(Ljava/io/InputStream;Lcom/tom/cpm/shared/config/Player;)Lcom/tom/cpm/shared/definition/ModelDefinition;",
            at = @At(value = "NEW", target = "(Lcom/tom/cpm/shared/definition/ModelDefinitionLoader;Lcom/tom/cpm/shared/config/Player;)Lcom/tom/cpm/shared/definition/ModelDefinition;"), require = 1, expect = 1, allow = 1)
    private ModelDefinition nclskins$owned(ModelDefinitionLoader<?> loader, Player<?> player, Operation<ModelDefinition> original) {
        var source = CpmIntegration.source(player);
        if (source == null) return original.call(loader, player);
        OwnedCpmDefinition owned = new OwnedCpmDefinition(loader, player, source);
        nclskins$decoding.set(owned);
        return owned;
    }

    @WrapOperation(method = "loadModel(Ljava/io/InputStream;Lcom/tom/cpm/shared/config/Player;)Lcom/tom/cpm/shared/definition/ModelDefinition;",
            at = @At(value = "INVOKE", target = "Lcom/tom/cpm/shared/network/NetHandler;requestPlayerState(Ljava/util/UUID;)V"), require = 1, expect = 1, allow = 1)
    private void nclskins$state(NetHandler<?, ?, ?> handler, UUID uuid, Operation<Void> original, InputStream input, Player<?> player) {
        var source = CpmIntegration.source(player);
        if (source == null || !source.preview() && source.active()) original.call(handler, uuid);
    }
}
