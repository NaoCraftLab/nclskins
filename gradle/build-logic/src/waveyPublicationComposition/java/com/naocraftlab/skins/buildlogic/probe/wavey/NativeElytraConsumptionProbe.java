package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.*;
import net.minecraft.client.model.object.equipment.ElytraModel;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

public final class NativeElytraConsumptionProbe {
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    public static void consume(ItemStack plain, ItemStack enchanted) throws Exception {
        check(plain != enchanted && !plain.isEnchanted() && !plain.hasFoil()
                && enchanted.isEnchanted() && enchanted.hasFoil()
                && plain.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) == null
                && enchanted.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) == null, "ELYTRA_NATIVE_STACK_PREREQUISITE");
        try (HeadlessCapeClient client = HeadlessCapeClient.open(false); AutoCloseable renderer = NativeElytraDrain.open()) {
            EquipmentAssetManager assets = new EquipmentAssetManager();
            var key = enchanted.get(DataComponents.EQUIPPABLE).assetId().orElseThrow();
            check(plain.get(DataComponents.EQUIPPABLE).assetId().orElseThrow().equals(key), "ELYTRA_NATIVE_ASSET_IDENTITY");
            var layer = new EquipmentClientInfo.Layer(Identifier.parse("nclskins:synthetic_elytra"));
            var field = EquipmentAssetManager.class.getDeclaredField("equipmentAssets"); field.setAccessible(true);
            field.set(assets, Map.of(key, NativeElytraDrain.equipment(layer)));
            EquipmentLayerRenderer equipment = new EquipmentLayerRenderer(assets, null);
            ElytraModel model = new ElytraModel(ElytraModel.createLayer().bakeRoot());
            for (ItemStack stack : List.of(plain, enchanted)) {
                HumanoidRenderState state = new HumanoidRenderState(); state.chestEquipment = stack;
                SubmitNodeStorage storage = new SubmitNodeStorage();
                equipment.renderLayers(EquipmentClientInfo.LayerType.WINGS, key, model, state, stack, new PoseStack(), storage, 157, 0);
                NativeElytraDrain.consume(storage, stack == enchanted, layer.getTextureLocation(EquipmentClientInfo.LayerType.WINGS));
                check(state.chestEquipment == stack && stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) == null,
                        "ELYTRA_STACK_IDENTITY_OR_OVERRIDE_CHANGED");
                System.out.println("NATIVE_ELYTRA_STACK_PASS " + (stack == enchanted ? "enchanted" : "plain")
                        + " same input/state identity; override absent; actual EquipmentLayerRenderer WINGS -> native family drain");
            }
        }
        System.out.println("NATIVE_ELYTRA_CONSUMPTION_PASS same plain/component-enchanted stacks -> actual EquipmentLayerRenderer WINGS/hasFoil -> actual ModelFeatureRenderer/native family drain; native foil 0/1; provider foil absent; CPU geometry sink; no override");
    }
}
