package com.naocraftlab.skins.buildlogic.probe;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import java.util.*;

public final class NativeCleanupProbe {
    static final class FaultTextures extends TextureManager {
        Error failure;
        int closes;
        FaultTextures() { super(null); }
        @Override public void close() { closes++; throw failure; }
    }
    static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        CpmBehaviorProbe.initializeHost(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"cleanup-immediate-config.json").toFile());
        String mode=args.length==0?"close":args[0];
        Throwable first=mode.equals("interrupt")?new InterruptedException("owned interrupted listener"):new AssertionError("owned listener failure");
        var later=mode.equals("same")?(Error)first:new AssertionError("owned texture failure");
        var body=new AssertionError("body failure");
        var backend=CpmNativeMeasurementBackend.class;
        CpmNativeMeasurementBackend.previous.put("client",CpmNativeMeasurementBackend.get(Minecraft.class,"instance",null));
        for(String field:List.of("DEVICE","renderThread","dynamicUniforms","projectionMatrixBuffer","projectionType","outputColorTextureOverride","outputDepthTextureOverride"))
            CpmNativeMeasurementBackend.previous.put(field,CpmNativeMeasurementBackend.get(RenderSystem.class,field,null));
        var prior=new LinkedHashMap<>(CpmNativeMeasurementBackend.previous);
        CpmNativeMeasurementBackend.originalHost=com.tom.cpm.shared.MinecraftClientAccess.get();
        var host=CpmNativeMeasurementBackend.originalHost;
        var textures=(FaultTextures)CpmNativeMeasurementBackend.allocate(FaultTextures.class);textures.failure=later;
        CpmNativeMeasurementBackend.textures=textures;
        var buffer=new SubmissionCpuBackend.Buffer(0,16);SubmissionCpuBackend.buffers.add(buffer);
        int[] events={0};
        CpmNativeMeasurementBackend.loaderEvents=()-> { events[0]++;if(first instanceof Error error)throw error;throw (Exception)first; };
        Throwable caught=null;
        try(var scope=CpmNativeMeasurementBackend.scope()) { if(args.length>0&&args[0].equals("body"))throw body; }
        catch(Throwable failure) { caught=failure; }
        check(caught==(args.length>0&&args[0].equals("body")?body:first),"immediate caller preserves exact first failure");
        Throwable cleanup=caught==body?caught.getSuppressed()[0]:caught;
        check(cleanup==first&&(mode.equals("same")?cleanup.getSuppressed().length==0:cleanup.getSuppressed().length==1&&cleanup.getSuppressed()[0]==later),"immediate caller retains exact suppressed failures");
        check(textures.closes==1&&events[0]==1&&buffer.isClosed(),"immediate caller attempts later owned resources once");
        for(var entry:prior.entrySet())check(CpmNativeMeasurementBackend.get(entry.getKey().equals("client")?Minecraft.class:RenderSystem.class,entry.getKey().equals("client")?"instance":entry.getKey(),null)==entry.getValue(),"immediate exact prior context restored");
        check(com.tom.cpm.shared.MinecraftClientAccess.get()==host,"immediate exact prior CPM host restored");
        if(mode.equals("interrupt"))check(Thread.interrupted(),"interrupted cleanup restores interrupt flag");
        CpmNativeMeasurementBackend.close();
        check(textures.closes==1&&events[0]==1,"failed immediate close remains exact-once");
        System.out.println("NATIVE_CLEANUP_IMMEDIATE_PASS body="+(caught==body)+" mode="+mode);
    }
}
