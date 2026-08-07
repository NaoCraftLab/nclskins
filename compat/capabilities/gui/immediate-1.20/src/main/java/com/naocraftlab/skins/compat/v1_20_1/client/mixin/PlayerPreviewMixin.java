package com.naocraftlab.skins.compat.v1_20_1.client.mixin;

import com.naocraftlab.skins.compat.v1_20_1.client.Minecraft1201PreviewScope;
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
        Boolean value = Minecraft1201PreviewScope.modelPart(self(), part);
        if (value != null) {
            callback.setReturnValue(value);
        }
    }

    @Inject(method = "getItemBySlot", at = @At("RETURN"), cancellable = true)
    private void nclskins$equipment(
            EquipmentSlot slot,
            CallbackInfoReturnable<ItemStack> callback) {
        callback.setReturnValue(Minecraft1201PreviewScope.equipment(
                self(), slot, callback.getReturnValue()));
    }

    private Player self() {
        return (Player) (Object) this;
    }
}
