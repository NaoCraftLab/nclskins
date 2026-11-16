package com.naocraftlab.skins.buildlogic.probe;

import java.util.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuSampler;

public final class NativeAbiCleanupProbe {
    static final Error first=new AssertionError("first owned ABI close");
    static final Error second=new AssertionError("later owned ABI close");
    static final int[] calls=new int[2];
    static final class Fog extends net.minecraft.client.renderer.fog.FogRenderer {
        @Override public void close() { calls[0]++; throw first; }
    }
    static final class Globals extends net.minecraft.client.renderer.GlobalSettingsUniform {
        @Override public void close() { calls[1]++; throw second; }
    }
    static final class Data extends net.minecraft.client.renderer.DynamicGpuData {
        @Override public void close() { calls[0]++; throw first; }
    }
    static final class Pipeline extends com.mojang.blaze3d.pipeline.PipelineCache {
        Pipeline() { super(null,null); }
        @Override public void close() { calls[1]++; throw second; }
    }
    static GpuSampler sampler(int index) {
        return PipCpuBackend.proxy(GpuSampler.class,(owner,method,args)-> {
            if(method.getName().equals("close")) { calls[index]++; throw index==0?first:second; }
            throw new AssertionError("sampler cleanup only");
        });
    }
    static final class BorrowedTextures extends net.minecraft.client.renderer.texture.TextureManager {
        BorrowedTextures() { super(null); }
        @Override public void close() { calls[0]++; }
    }
    static final class BorrowedFog extends net.minecraft.client.renderer.fog.FogRenderer {
        @Override public void close() { calls[1]++; }
    }
    static void borrowedScope() throws Exception {
        var textures=(BorrowedTextures)PipMaterialProbe.allocate(BorrowedTextures.class);
        var client=(net.minecraft.client.Minecraft)PipMaterialProbe.allocate(net.minecraft.client.Minecraft.class);
        var fog=(BorrowedFog)PipMaterialProbe.allocate(BorrowedFog.class);
        PipMaterialProbe.textures=textures;PipMaterialProbe.minecraft=client;NativeFixtureAbi.fog=fog;
        PipMaterialProbe.set(net.minecraft.client.Minecraft.class,"instance",null,client);
        PipMaterialProbe.set(RenderSystem.class,"renderThread",null,Thread.currentThread());
        new PipProcessState().close();
        NativeCleanupProbe.check(calls[0]==0&&calls[1]==0&&PipMaterialProbe.textures==textures&&PipMaterialProbe.minecraft==client&&NativeFixtureAbi.fog==fog,"unmodified borrowed fixture resources preserved");
        System.out.println("NATIVE_ABI_CLEANUP_PASS borrowed");
    }
    public static void main(String[] args) throws Exception {
        if(args[0].equals("borrowed")) { borrowedScope();return; }
        Throwable caught=null;
        var previous=new LinkedHashMap<String,Object>();
        for(String field:List.of("dynamicGpuData","currentPipelineCache"))previous.put(field,PipMaterialProbe.get(RenderSystem.class,field,null));
        var fog=NativeFixtureAbi.fog;
        var globals=NativeFixtureAbi.globals;
        GpuSampler[] live={sampler(0),sampler(1)};
        GpuSampler[] old={null,null};
        try {
            try {
                switch(args[0]) {
                    case "auxiliary" -> {
                        NativeFixtureAbi.fog=(Fog)PipMaterialProbe.allocate(Fog.class);
                        NativeFixtureAbi.globals=(Globals)PipMaterialProbe.allocate(Globals.class);
                        NativeFixtureAbi.closeAuxiliary();
                    }
                    case "render-data" -> {
                        PipMaterialProbe.set(RenderSystem.class,"dynamicGpuData",null,PipMaterialProbe.allocate(Data.class));
                        PipMaterialProbe.set(RenderSystem.class,"currentPipelineCache",null,PipMaterialProbe.allocate(Pipeline.class));
                        NativeFixtureAbi.closeRenderData(previous);
                    }
                    case "samplers" -> NativeFixtureAbi.restoreSamplers(new NativeFixtureAbi.Samplers(live,old));
                    default -> throw new AssertionError("known cleanup case");
                }
            } catch(Throwable failure) { caught=failure; }
            NativeCleanupProbe.check(caught==first,"ABI close retains first exact failure");
            NativeCleanupProbe.check(calls[0]==1&&calls[1]==1,"ABI inner loop attempts every owned close once");
            NativeCleanupProbe.check(first.getSuppressed().length==1&&first.getSuppressed()[0]==second,"ABI close suppresses later exact failure");
            if(args[0].equals("samplers"))NativeCleanupProbe.check(live[0]==old[0]&&live[1]==old[1],"all prior sampler entries restored despite close failure");
            System.out.println("NATIVE_ABI_CLEANUP_PASS "+args[0]);
        } finally {
            for(var entry:previous.entrySet())PipMaterialProbe.set(RenderSystem.class,entry.getKey(),null,entry.getValue());
            NativeFixtureAbi.fog=fog;
            NativeFixtureAbi.globals=globals;
        }
    }
}
