package com.naocraftlab.skins.mc1211.mixin;

import com.naocraftlab.skins.mc1211.Minecraft1211PreviewScope;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
abstract class PlayerPreviewMixin {
    @Inject(method = "isModelPartShown", at = @At("RETURN"), cancellable = true)
    private void nclskins$modelPart(
            PlayerModelPart part,
            CallbackInfoReturnable<Boolean> callback) {
        Player player = (Player) (Object) this;
        Boolean value = Minecraft1211PreviewScope.modelPart(player, part);
        if (value != null) {
            callback.setReturnValue(value);
        }
    }

    @Inject(method = "getItemBySlot", at = @At("RETURN"), cancellable = true)
    private void nclskins$equipment(
            EquipmentSlot slot,
            CallbackInfoReturnable<ItemStack> callback) {
        Player player = (Player) (Object) this;
        callback.setReturnValue(Minecraft1211PreviewScope.equipment(
                player, slot, callback.getReturnValue()));
    }
}
