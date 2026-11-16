package com.naocraftlab.skins.buildlogic.probe;

import java.util.List;
import java.util.Map;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.resources.Identifier;

public final class NativeFixtureAbi {
    public static List<String> stateFields() { return List.of("DEVICE","renderThread","dynamicUniforms","shaderLightDirections","projectionMatrixBuffer","projectionType","outputColorTextureOverride","outputDepthTextureOverride"); }
    public static Object captureSamplers() { return null; }
    public static void captureOwnedSamplers(Object state) { }
    public static void closeAuxiliary() { }
    public static Object captureAuxiliary() { return null; }
    public static AutoCloseable auxiliaryScope(Object previous) { return ()->{}; }
    public static void closeRenderData(Map<String,Object> previous) throws Exception {
        Object uniforms=PipMaterialProbe.get(com.mojang.blaze3d.systems.RenderSystem.class,"dynamicUniforms",null);
        if(uniforms instanceof AutoCloseable owned&&uniforms!=previous.get("dynamicUniforms"))owned.close();
    }
    public static void closeRenderData(Map<String,Object> previous,Map<String,Object> captured) throws Exception {
        Object uniforms=captured.get("dynamicUniforms");
        if(uniforms instanceof AutoCloseable owned&&uniforms!=previous.get("dynamicUniforms"))owned.close();
    }
    public static void restoreSamplers(Object state) { if(state!=null)throw new AssertionError("unexpected sampler snapshot"); }
    public static void installRender() throws Exception {
        PipMaterialProbe.set(com.mojang.blaze3d.systems.RenderSystem.class,"dynamicUniforms",null,new net.minecraft.client.renderer.DynamicUniforms());
    }
    public static void installGame(Object raw,net.minecraft.client.renderer.GameRenderer game) throws Exception {
        var device=(com.mojang.blaze3d.systems.GpuDevice)raw;
        var light=(net.minecraft.client.renderer.Lightmap)PipMaterialProbe.allocate(net.minecraft.client.renderer.Lightmap.class);
        var texture=device.createTexture("readonly CPU lightmap",13,com.mojang.blaze3d.GpuFormat.RGBA8_UNORM,16,16,1,1);
        PipMaterialProbe.set(net.minecraft.client.renderer.Lightmap.class,"texture",light,texture);
        PipMaterialProbe.set(net.minecraft.client.renderer.Lightmap.class,"textureView",light,device.createTextureView(texture));
        PipMaterialProbe.set(net.minecraft.client.renderer.GameRenderer.class,"lightmap",game,light);
        PipMaterialProbe.set(net.minecraft.client.renderer.GameRenderer.class,"uiLightmap",game,new net.minecraft.client.renderer.UiLightmap());
        PipMaterialProbe.set(net.minecraft.client.renderer.GameRenderer.class,"mainRenderTarget",game,new com.mojang.blaze3d.pipeline.TextureTarget("CPU readonly depth-format target",16,16,true,com.mojang.blaze3d.GpuFormat.RGBA8_UNORM));
    }
    public static void uv3(VertexConsumer delegate,float u,float v) { throw new AssertionError("UV3 is not present in selected ABI"); }
    public static Identifier armorAtlas() { return net.minecraft.data.AtlasIds.ARMOR_TRIMS; }
    public static EntityRendererProvider.Context context(EntityModelSet models) {
        return new EntityRendererProvider.Context(null,null,null,null,null,models,new EquipmentAssetManager(),null,null,null);
    }
    public static net.minecraft.core.component.DataComponentPatch equipmentPatch(net.minecraft.world.item.equipment.Equippable value) throws Exception {
        if(!"neoforge".equals(System.getProperty("nclskins.upstream.family")))return net.minecraft.core.component.DataComponentPatch.builder().set(net.minecraft.core.component.DataComponents.EQUIPPABLE,value).set(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE,false).build();
        var map=new it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap<net.minecraft.core.component.DataComponentType<?>,java.util.Optional<?>>();
        map.put(net.minecraft.core.component.DataComponents.EQUIPPABLE,java.util.Optional.of(value));
        map.put(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE,java.util.Optional.of(false));
        var ctor=net.minecraft.core.component.DataComponentPatch.class.getDeclaredConstructor(it.unimi.dsi.fastutil.objects.Reference2ObjectMap.class);ctor.setAccessible(true);return ctor.newInstance(map);
    }
    public static EquipmentClientInfo equipment(Map<EquipmentClientInfo.LayerType,List<EquipmentClientInfo.Layer>> layers) {
        return new EquipmentClientInfo(layers);
    }
}
