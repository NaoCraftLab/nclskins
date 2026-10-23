package com.naocraftlab.skins.buildlogic.probe.wavey;

import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.equipment.Equippable;

final class NativeEnchantmentInput {
    static ItemStack elytra() {
        return new ItemStack(Holder.direct(Items.ELYTRA, DataComponentMap.builder()
                .set(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY)
                .set(DataComponents.EQUIPPABLE, Equippable.builder(EquipmentSlot.CHEST)
                        .setAsset(EquipmentAssets.ELYTRA).build())
                .build()));
    }
}
