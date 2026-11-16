package com.naocraftlab.skins.buildlogic.probe;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;

public final class NativeCleanupProbe {
    static final class FaultTextures extends TextureManager {
        final Error failure;
        int closes;
        FaultTextures(Error failure) { super(null); this.failure=failure; }
        @Override public void close() { closes++; throw failure; }
    }
    static final class FaultFeatures extends net.minecraft.client.renderer.feature.FeatureRenderDispatcher {
        Error failure;
        int closes;
        FaultFeatures() { super(null,null,null,null,null); }
        @Override public void close() { closes++; throw failure; }
    }
    static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    static void scopeFailure() throws Exception {
        CpmBehaviorProbe.initializeHost(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"cleanup-scope-config.json").toFile());
        var failure=new AssertionError("feature close failure");
        var state=new PipProcessState();
        PipMaterialProbe.set(RenderSystem.class,"renderThread",null,Thread.currentThread());
        var feature=(FaultFeatures)PipMaterialProbe.allocate(FaultFeatures.class);feature.failure=failure;
        CpmNativeMeasurementBackend.features=feature;
        CpmNativeMeasurementBackend.processState=state;
        CpmNativeMeasurementBackend.originalHost=state.clientAccess;
        var buffer=new PipCpuBackend.Buffer(0,16);PipCpuBackend.buffers.add(buffer);
        PipMaterialProbe.minecraft=null;PipMaterialProbe.textures=null;PipMaterialProbe.vertices=null;
        Throwable caught=null;
        try(var scope=CpmNativeMeasurementBackend.scope()) { }
        catch(Throwable value) { caught=value; }
        if(caught!=failure)throw new AssertionError("selected caller preserves exact first feature close failure",caught);
        check(buffer.isClosed()&&state.closed&&feature.closes==1,"selected caller attempts process close after feature failure");
        check(PipMaterialProbe.get(RenderSystem.class,"renderThread",null)==state.previous.get("renderThread"),"selected caller restores exact prior context");
        System.out.println("NATIVE_CLEANUP_SCOPE_PASS");
    }
    public static void main(String[] args) throws Exception {
        if(args.length>0&&args[0].equals("scope")) { scopeFailure();return; }
        var first=new AssertionError("owned texture failure");
        var later=new AssertionError("owned event failure");
        var body=new AssertionError("body failure");
        var state=new PipProcessState();
        var textures=(FaultTextures)PipMaterialProbe.allocate(FaultTextures.class);
        PipMaterialProbe.set(FaultTextures.class,"failure",textures,first);
        var buffer=new PipCpuBackend.Buffer(0,16);
        PipCpuBackend.buffers.add(buffer);
        PipMaterialProbe.textures=textures;
        PipMaterialProbe.minecraft=null;
        PipMaterialProbe.set(RenderSystem.class,"renderThread",null,Thread.currentThread());
        int[] eventCloses={0};
        state.neoEvents=()-> { eventCloses[0]++; throw later; };
        Throwable caught=null;
        try {
            if(args.length>0&&args[0].equals("body")) {
                try(state) { throw body; }
            } else state.close();
        } catch(Throwable failure) { caught=failure; }
        check(caught==(args.length>0&&args[0].equals("body")?body:first),"real close preserves exact first throwable identity");
        Throwable cleanup=caught==body?caught.getSuppressed()[0]:caught;
        check(cleanup==first&&cleanup.getSuppressed().length==1&&cleanup.getSuppressed()[0]==later,"real close retains exact later failure as suppressed");
        check(textures.closes==1&&eventCloses[0]==1&&buffer.isClosed(),"later owned actions attempted once after early close fault");
        for(var entry:state.previous.entrySet())check(PipMaterialProbe.get(RenderSystem.class,entry.getKey(),null)==entry.getValue(),"all exact prior native bindings restored");
        check(PipMaterialProbe.get(Minecraft.class,"instance",null)==state.client,"exact prior client restored after fault");
        check(com.tom.cpm.shared.MinecraftClientAccess.get()==state.clientAccess,"exact prior CPM host restored after fault");
        state.close();
        check(textures.closes==1&&eventCloses[0]==1,"failed process close remains exact-once");
        var next=new PipProcessState();
        PipMaterialProbe.set(RenderSystem.class,"renderThread",null,Thread.currentThread());
        CpmNativeMeasurementBackend.processState=next;
        try(var scope=CpmNativeMeasurementBackend.scope()) { }
        check(next.closed,"next selected native scope is usable after failed body");
        System.out.println("NATIVE_CLEANUP_FAULT_PASS body="+(caught==body));
    }
}
