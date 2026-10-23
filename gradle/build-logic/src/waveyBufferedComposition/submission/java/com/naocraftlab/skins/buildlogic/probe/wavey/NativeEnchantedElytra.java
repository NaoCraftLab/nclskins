package com.naocraftlab.skins.buildlogic.probe.wavey;

import net.minecraft.core.*;
import net.minecraft.core.component.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantment;

public final class NativeEnchantedElytra {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        consume();
    }
    public static void consume() throws Exception {
        var plain = new ItemStack(Items.ELYTRA);
        var enchanted = new ItemStack(Items.ELYTRA);
        var definition = Enchantment.definition(HolderSet.direct(Items.ELYTRA.builtInRegistryHolder()), 1, 1,
                Enchantment.constantCost(1), Enchantment.constantCost(1), 1, EquipmentSlotGroup.CHEST);
        var enchantment = new Enchantment(Component.literal("Synthetic test enchantment"), definition, HolderSet.direct(), DataComponentMap.EMPTY);
        enchanted.enchant(Holder.direct(enchantment), 1);
        NativeElytraConsumptionProbe.consume(plain, enchanted);
    }
}
