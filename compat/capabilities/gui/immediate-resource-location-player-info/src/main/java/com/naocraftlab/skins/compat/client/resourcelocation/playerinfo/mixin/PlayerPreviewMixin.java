package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.mixin;

import com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.PreviewScope;
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
        Boolean value = PreviewScope.modelPart(self(), part);
        return value == null ? original : value;
    }

    @ModifyReturnValue(method = "getItemBySlot", at = @At("RETURN"))
    private ItemStack nclskins$equipment(ItemStack original, EquipmentSlot slot) {
        return PreviewScope.equipment(self(), slot, original);
    }

    private Player self() {
        return (Player) (Object) this;
    }
}
