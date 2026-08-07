package com.naocraftlab.skins.compat.mc262.mixin;

import com.naocraftlab.skins.compat.mc262.Minecraft262PreviewScope;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
abstract class LivingEntityPreviewMixin {
    @Inject(method = "getItemBySlot", at = @At("RETURN"), cancellable = true)
    private void nclskins$equipment(
            EquipmentSlot slot,
            CallbackInfoReturnable<ItemStack> callback) {
        LivingEntity entity = (LivingEntity) (Object) this;
        callback.setReturnValue(Minecraft262PreviewScope.equipment(
                entity, slot, callback.getReturnValue()));
    }
}
