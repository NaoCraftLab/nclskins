package com.naocraftlab.skins.buildlogic.probe;

import java.util.*;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.resources.model.*;
import net.minecraft.client.renderer.entity.layers.*;
import net.minecraft.resources.*;
import net.minecraft.core.component.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.equipment.*;
import static com.naocraftlab.skins.buildlogic.probe.PipMaterialProbe.*;

public final class ReferenceEquipment {
    static ItemStack owned;
    public static boolean isOwned(ItemStack stack){return stack==owned&&owned!=null;}
    public static void configure(AvatarRenderState state)throws Exception{
        var id=Identifier.parse("nclskins:synthetic-public-wing");
        ResourceKey<EquipmentAsset> key=ResourceKey.create(ResourceKey.createRegistryKey(Identifier.parse("minecraft:equipment_asset")),id);
        var equippable=new Equippable(net.minecraft.world.entity.EquipmentSlot.CHEST,null,Optional.of(key),Optional.empty(),Optional.empty(),false,false,false,false,false,null);
        DataComponentPatch patch=NativeFixtureAbi.equipmentPatch(equippable);
        owned=(ItemStack)allocate(ItemStack.class);set(ItemStack.class,"components",owned,PatchedDataComponentMap.fromPatch(DataComponentMap.EMPTY,patch));set(ItemStack.class,"count",owned,1);state.chestEquipment=owned;
        var material=new EquipmentClientInfo.Layer(Identifier.parse("cpm:template/cape"),Optional.empty(),true);var info=NativeFixtureAbi.equipment(Map.of(EquipmentClientInfo.LayerType.WINGS,List.of(material)));
        @SuppressWarnings("unchecked")var layers=(List<Object>)get(net.minecraft.client.renderer.entity.LivingEntityRenderer.class,"layers",avatar);
        for(Object layer:layers){
            if(layer instanceof WingsLayer){var renderer=(EquipmentLayerRenderer)get(WingsLayer.class,"equipmentRenderer",layer);var manager=(EquipmentAssetManager)get(EquipmentLayerRenderer.class,"equipmentAssets",renderer);set(EquipmentAssetManager.class,"equipmentAssets",manager,Map.of(key,info));}
            if(layer instanceof CapeLayer){var manager=(EquipmentAssetManager)get(CapeLayer.class,"equipmentAssets",layer);set(EquipmentAssetManager.class,"equipmentAssets",manager,Map.of(key,info));}
        }
        if(state.skin.elytra()==null)state.skin=new net.minecraft.world.entity.player.PlayerSkin(state.skin.body(),state.skin.cape(),new net.minecraft.core.ClientAsset.DownloadedTexture(Identifier.parse("cpm:textures/template/cape.png"),"synthetic:public-cpm-material"),state.skin.model(),false);
    }
}
