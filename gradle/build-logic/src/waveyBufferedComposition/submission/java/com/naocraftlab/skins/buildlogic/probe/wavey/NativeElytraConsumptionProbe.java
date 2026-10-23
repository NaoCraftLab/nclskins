package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.compat.client.identifier.foil.CapturedCapePass;
import java.util.*;
import net.minecraft.client.model.object.equipment.ElytraModel;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.equipment.EquipmentAsset;

public final class NativeElytraConsumptionProbe {
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static final class Buffers extends MultiBufferSource.BufferSource {
        final List<RenderType> acquired = new ArrayList<>();
        Buffers() { super(null, new LinkedHashMap<>()); }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer getBuffer(RenderType type) {
            acquired.add(type); return super.getBuffer(type);
        }
    }
    public static void consume(ItemStack plain, ItemStack enchanted) throws Exception {
        check(!plain.isEnchanted() && !plain.hasFoil() && enchanted.isEnchanted() && enchanted.hasFoil()
                && enchanted.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) == null, "ELYTRA_NATIVE_STACK_PREREQUISITE");
        try (HeadlessCapeClient client = HeadlessCapeClient.open(false)) {
        EquipmentAssetManager assets = new EquipmentAssetManager();
        var key = enchanted.get(DataComponents.EQUIPPABLE).assetId().orElseThrow();
        var texture = Identifier.parse("nclskins:synthetic_elytra");
        var field = EquipmentAssetManager.class.getDeclaredField("equipmentAssets"); field.setAccessible(true);
        field.set(assets, Map.of(key, new EquipmentClientInfo(Map.of(EquipmentClientInfo.LayerType.WINGS,
                List.of(new EquipmentClientInfo.Layer(texture))))));
        EquipmentLayerRenderer equipment = new EquipmentLayerRenderer(assets, null);
        ElytraModel model = new ElytraModel(ElytraModel.createLayer().bakeRoot());
        for (ItemStack stack : List.of(plain, enchanted)) {
            HumanoidRenderState state = new HumanoidRenderState(); state.chestEquipment = stack;
            SubmitNodeStorage storage = new SubmitNodeStorage();
            equipment.renderLayers(EquipmentClientInfo.LayerType.WINGS, key, model, state, stack, new PoseStack(), storage, 157, 0);
            Buffers buffers = new Buffers(); ModelFeatureRenderer consumer = new ModelFeatureRenderer();
            int draws = BufferedCapeHost.draws, closes = BufferedCapeHost.closes;
            for (var collection : storage.getSubmitsPerOrder().values()) consumer.render(collection, buffers, null, buffers);
            buffers.endBatch();
            check(buffers.acquired.stream().filter(t -> t == RenderTypes.armorEntityGlint()).count() == (stack == enchanted ? 1 : 0),
                    "ELYTRA_NATIVE_FOIL_MISSING_OR_DUPLICATED");
            check(buffers.acquired.stream().noneMatch(t -> CapturedCapePass.of(t) != null)
                    && BufferedCapeHost.draws - draws == (stack == enchanted ? 2 : 1)
                    && BufferedCapeHost.closes - closes == (stack == enchanted ? 2 : 1), "ELYTRA_PROVIDER_FOIL_OR_DRAIN_CHANGED");
            check(state.chestEquipment == stack && stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) == null,
                    "ELYTRA_STACK_IDENTITY_OR_OVERRIDE_CHANGED");
            BufferedCapeHost.verifyClosed();
        }
        }
        System.out.println("NATIVE_ELYTRA_CONSUMPTION_PASS same plain/component-enchanted stacks -> actual EquipmentLayerRenderer WINGS/hasFoil -> actual ModelFeatureRenderer/native getBuffer/endBatch; armorEntityGlint 0/1; provider foil absent; CPU geometry sink; no override");
    }
}
