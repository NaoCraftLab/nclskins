package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.mixin;

import com.naocraftlab.skins.compat.client.resourcelocation.skinlookup.PreviewScope;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
abstract class PlayerPreviewMixin {
    @ModifyReturnValue(method = "isModelPartShown", at = @At("RETURN"))
    private boolean nclskins$modelPart(boolean original, PlayerModelPart part) {
        Player player = (Player) (Object) this;
        Boolean value = PreviewScope.modelPart(player, part);
        return value == null ? original : value;
    }

    @ModifyReturnValue(method = "getItemBySlot", at = @At("RETURN"))
    private ItemStack nclskins$equipment(ItemStack original, EquipmentSlot slot) {
        Player player = (Player) (Object) this;
        return PreviewScope.equipment(player, slot, original);
    }
}
