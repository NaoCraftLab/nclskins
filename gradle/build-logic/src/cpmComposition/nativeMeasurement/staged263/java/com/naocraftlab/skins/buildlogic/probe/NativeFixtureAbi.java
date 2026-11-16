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
    static net.minecraft.client.renderer.fog.FogRenderer fog;
    static net.minecraft.client.renderer.GlobalSettingsUniform globals;
    record Samplers(com.mojang.renderpearl.api.textures.GpuSampler[] live,com.mojang.renderpearl.api.textures.GpuSampler[] previous,com.mojang.renderpearl.api.textures.GpuSampler[] owned) {
        Samplers(com.mojang.renderpearl.api.textures.GpuSampler[] live,com.mojang.renderpearl.api.textures.GpuSampler[] previous) { this(live,previous,live.clone()); }
    }
    public static void captureOwnedSamplers(Object value) {
        var state=(Samplers)value;
        System.arraycopy(state.live,0,state.owned,0,state.live.length);
    }
    public static List<String> stateFields() { return List.of("DEVICE","renderThread","dynamicGpuData","currentPipelineCache","globalSettingsUniform","shaderFog","shaderLightDirections","projectionMatrixBuffer","projectionType"); }
    public static Object captureSamplers() throws Exception {
        var live=(com.mojang.renderpearl.api.textures.GpuSampler[])PipMaterialProbe.get(com.mojang.blaze3d.systems.SamplerCache.class,"samplers",com.mojang.blaze3d.systems.RenderSystem.getSamplerCache());
        return new Samplers(live,live.clone());
    }
    public static void closeAuxiliary() throws Exception { auxiliaryScope().close(); }
    record Auxiliary(net.minecraft.client.renderer.fog.FogRenderer fog,net.minecraft.client.renderer.GlobalSettingsUniform globals) {}
    public static Object captureAuxiliary() { return new Auxiliary(fog,globals); }
    public static AutoCloseable auxiliaryScope() { return auxiliaryScope(null); }
    public static AutoCloseable auxiliaryScope(Object value) {
        var previous=(Auxiliary)value;
        var ownedFog=previous!=null&&previous.fog==fog?null:fog;
        var ownedGlobals=previous!=null&&previous.globals==globals?null:globals;
        return () -> {
            if(ownedFog!=null&&fog==ownedFog)fog=null;
            if(ownedGlobals!=null&&globals==ownedGlobals)globals=null;
            try(var cleanup=new FixtureCleanup()) {
                cleanup.attempt(()->{if(ownedFog!=null)ownedFog.close();});
                cleanup.attempt(()->{if(ownedGlobals!=null)ownedGlobals.close();});
            }
        };
    }
    public static void closeRenderData(Map<String,Object> previous) throws Exception {
        var owned=new java.util.LinkedHashMap<String,Object>();
        try(var cleanup=new FixtureCleanup()) {
            for(String field:List.of("dynamicGpuData","currentPipelineCache"))cleanup.attempt(()->owned.put(field,PipMaterialProbe.get(com.mojang.blaze3d.systems.RenderSystem.class,field,null)));
            cleanup.attempt(()->closeRenderData(previous,owned));
        }
    }
    public static void closeRenderData(Map<String,Object> previous,Map<String,Object> captured) throws Exception {
        try(var cleanup=new FixtureCleanup()) {
            for(String field:List.of("dynamicGpuData","currentPipelineCache"))cleanup.attempt(()-> {
                Object value=captured.get(field);
                if(value instanceof AutoCloseable owned&&value!=previous.get(field))owned.close();
            });
        }
    }
    public static void restoreSamplers(Object value) {
        var state=(Samplers)value;
        try(var cleanup=new FixtureCleanup()) {
            for(int i=0;i<state.live.length;i++) {
                int index=i;
                cleanup.attempt(()-> {
                    var owned=state.owned[index];
                    if(state.live[index]==owned) {
                        state.live[index]=state.previous[index];
                        PipMaterialProbe.check(state.live[index]==state.previous[index],"exact prior native sampler-cache entry restored");
                    }
                    if(owned!=state.previous[index]&&owned!=null)owned.close();
                });
            }

        }
    }
    public static void installRender() throws Exception {
        var device=(com.mojang.renderpearl.api.device.GpuDevice)PipMaterialProbe.get(com.mojang.blaze3d.systems.RenderSystem.class,"DEVICE",null);
        com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().initialize();
        PipMaterialProbe.set(com.mojang.blaze3d.systems.RenderSystem.class,"dynamicGpuData",null,new net.minecraft.client.renderer.DynamicGpuData());
        fog=new net.minecraft.client.renderer.fog.FogRenderer();
        globals=new net.minecraft.client.renderer.GlobalSettingsUniform();
        globals.update(16,16,1.0,0L,0F,0,net.minecraft.world.phys.Vec3.ZERO,false);
        var shader=PipCpuBackend.proxy(com.mojang.renderpearl.api.pipeline.ShaderSource.class,(o,m,a)->{if(m.getName().equals("close")){CompileHandleProbe.sourceCloses++;return null;}CompileHandleProbe.sourceReads++;throw new AssertionError("shader source forbidden");});
        PipMaterialProbe.set(com.mojang.blaze3d.systems.RenderSystem.class,"currentPipelineCache",null,new com.mojang.blaze3d.pipeline.PipelineCache(device,shader));
    }
    public static void installGame(Object raw,net.minecraft.client.renderer.GameRenderer game) throws Exception {
        var device=(com.mojang.renderpearl.api.device.GpuDevice)raw;
        PipMaterialProbe.set(net.minecraft.client.renderer.GameRenderer.class,"gameRenderState",game,new net.minecraft.client.renderer.state.GameRenderState());
        var light=(net.minecraft.client.renderer.Lightmap)PipMaterialProbe.allocate(net.minecraft.client.renderer.Lightmap.class);
        var texture=device.createTexture("readonly CPU lightmap",13,com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM,16,16,1,1);
        PipMaterialProbe.set(net.minecraft.client.renderer.Lightmap.class,"texture",light,texture);
        PipMaterialProbe.set(net.minecraft.client.renderer.Lightmap.class,"textureView",light,device.createTextureView(texture));
        PipMaterialProbe.set(net.minecraft.client.renderer.GameRenderer.class,"lightmap",game,light);
        PipMaterialProbe.set(net.minecraft.client.renderer.GameRenderer.class,"uiLightmap",game,new net.minecraft.client.renderer.UiLightmap());
        PipMaterialProbe.set(net.minecraft.client.renderer.GameRenderer.class,"mainRenderTarget",game,new com.mojang.blaze3d.pipeline.TextureTarget("CPU readonly depth-format target",16,16,com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM,com.mojang.renderpearl.api.GpuFormat.D32_FLOAT));
    }
    public static void uv3(VertexConsumer delegate,float u,float v) { delegate.setUv3(u,v); }
    public static Identifier armorAtlas() { return Identifier.parse("minecraft:armor_trims"); }
    public static EntityRendererProvider.Context context(EntityModelSet models) {
        return new EntityRendererProvider.Context(null,null,null,null,null,models,new EquipmentAssetManager(),null,null,null,null);
    }
    public static net.minecraft.core.component.DataComponentPatch equipmentPatch(net.minecraft.world.item.equipment.Equippable value) throws Exception {
        if(!"neoforge".equals(System.getProperty("nclskins.upstream.family")))return net.minecraft.core.component.DataComponentPatch.builder().set(net.minecraft.core.component.DataComponents.EQUIPPABLE,value).set(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE,false).build();
        var map=new it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap<net.minecraft.core.component.DataComponentType<?>,Object>();
        map.put(net.minecraft.core.component.DataComponents.EQUIPPABLE,value);
        map.put(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE,false);
        var ctor=net.minecraft.core.component.DataComponentPatch.class.getDeclaredConstructor(it.unimi.dsi.fastutil.objects.Reference2ObjectMap.class);ctor.setAccessible(true);return ctor.newInstance(map);
    }
    public static EquipmentClientInfo equipment(Map<EquipmentClientInfo.LayerType,List<EquipmentClientInfo.Layer>> layers) {
        return new EquipmentClientInfo(layers,List.of());
    }
}
