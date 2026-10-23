package com.naocraftlab.skins.compat.cpm.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.naocraftlab.skins.client.PreviewModelFrames;
import com.naocraftlab.skins.compat.cpm.CpmFrame;
import com.naocraftlab.skins.compat.cpm.OwnedCpmDefinition;
import com.tom.cpm.shared.animation.AnimationState;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.model.render.ModelRenderManager;
import java.util.IdentityHashMap;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

@Pseudo
@Mixin(targets = "com.tom.cpm.shared.model.render.ModelRenderManager", remap = false)
abstract class ModelRenderManagerMixin {
    @Unique private final Map<Object, PreviewModelFrames.Lease> nclskins$leases = new IdentityHashMap<>();

    @SuppressWarnings({"rawtypes", "unchecked"})
    @WrapMethod(method = "bindModel(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;Lcom/tom/cpm/shared/definition/ModelDefinition;Lcom/tom/cpm/shared/config/Player;Lcom/tom/cpm/shared/animation/AnimationState;)V")
    private void nclskins$bind(Object model, String arg, Object data, ModelDefinition definition,
            Player<?> player, AnimationState animation, Operation<Void> original) {
        if (!(definition instanceof OwnedCpmDefinition owned)) {
            original.call(model, arg, data, definition, player, animation);
            return;
        }
        ModelRenderManager manager = (ModelRenderManager) (Object) this;
        PreviewModelFrames.Lease lease = CpmFrame.accepts(owned) ? owned.acquire() : null;
        if (lease == null) { manager.unbindModel(model); return; }
        PreviewModelFrames.Lease previous = nclskins$leases.put(model, lease);
        try {
            original.call(model, arg, data, definition, player, animation);
            CpmFrame.bound(manager, model, arg, owned, player, () -> manager.unbindModel(model));
            if (previous != null) previous.close();
        } catch (RuntimeException | Error failure) {
            try { if (previous != null) previous.close(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            try { manager.unbindModel(model); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @WrapMethod(method = "bindSkin(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;Lcom/tom/cpm/shared/model/TextureSheetType;)V")
    private void nclskins$accessoryMaterial(Object model, String arg, Object texture,
            com.tom.cpm.shared.model.TextureSheetType sheet, Operation<Void> original) {
        if (sheet == com.tom.cpm.shared.model.TextureSheetType.CAPE
                || sheet == com.tom.cpm.shared.model.TextureSheetType.ELYTRA) {
            var bound = ((ModelRenderManager) (Object) this).getBoundPlayer(model, arg);
            if (bound != null && !com.naocraftlab.skins.compat.cpm.CpmIntegration.accessories(bound.player, bound.definition)) return;
        }
        original.call(model, arg, texture, sheet);
    }

    @WrapMethod(method = "unbindModel(Ljava/lang/Object;)V")
    private void nclskins$unbind(Object model, Operation<Void> original) {
        try { original.call(model); }
        finally {
            PreviewModelFrames.Lease lease = nclskins$leases.remove(model);
            if (lease != null) lease.close();
        }
    }
}
