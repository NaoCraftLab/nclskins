package com.naocraftlab.skins.compat.client.identifier.extraction.mixin;

import com.naocraftlab.skins.compat.client.identifier.extraction.AvatarPreviewScope;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
abstract class LivingEntityPreviewMixin {
    @ModifyReturnValue(method = "getItemBySlot", at = @At("RETURN"))
    private ItemStack nclskins$equipment(ItemStack original, EquipmentSlot slot) {
        LivingEntity entity = (LivingEntity) (Object) this;
        return AvatarPreviewScope.equipment(entity, slot, original);
    }
}
