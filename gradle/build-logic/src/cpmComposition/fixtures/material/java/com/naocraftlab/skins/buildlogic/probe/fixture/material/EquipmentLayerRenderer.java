package com.naocraftlab.skins.buildlogic.probe.fixture.material;
import com.naocraftlab.skins.buildlogic.probe.fixture.material.EquipmentClientInfo.LayerType;
import net.minecraft.resources.*;
import com.naocraftlab.skins.buildlogic.probe.fixture.material.Model;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.EquipmentAsset;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import com.naocraftlab.skins.buildlogic.probe.fixture.material.RenderTypes;
public class EquipmentLayerRenderer {
 public void renderLayers(LayerType type,ResourceKey<EquipmentAsset> asset,Model<Object> model,Object state,ItemStack item,PoseStack pose,SubmitNodeCollector submit,int light,Identifier fallback,int outline,int order) {
  Identifier selected=fallback;
  RenderTypes.armorCutoutNoCull(selected);
 }
}
