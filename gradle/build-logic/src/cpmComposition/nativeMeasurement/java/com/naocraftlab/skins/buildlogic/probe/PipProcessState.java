package com.naocraftlab.skins.buildlogic.probe;

import java.util.*;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.UiLightmap;
import net.minecraft.client.renderer.texture.TextureManager;

public final class PipProcessState implements AutoCloseable {
    final Map<String,Object> previous=new LinkedHashMap<>();
    final Object client;
    final Object samplers;
    final TextureManager borrowedTextures=PipMaterialProbe.textures;
    final Minecraft borrowedMinecraft=PipMaterialProbe.minecraft;
    final Object borrowedAuxiliary=NativeFixtureAbi.captureAuxiliary();
    final com.tom.cpm.shared.MinecraftClientAccess clientAccess;
    AutoCloseable neoEvents;
    final Set<Object> borrowedHardware=Collections.newSetFromMap(new IdentityHashMap<>());
    PipProcessState()throws Exception{
        borrowedHardware.addAll(PipCpuBackend.views);borrowedHardware.addAll(PipCpuBackend.textures);borrowedHardware.addAll(PipCpuBackend.buffers);
        clientAccess=com.tom.cpm.shared.MinecraftClientAccess.get();
        client=PipMaterialProbe.get(Minecraft.class,"instance",null);
        samplers=NativeFixtureAbi.captureSamplers();
        for(String field:NativeFixtureAbi.stateFields())previous.put(field,PipMaterialProbe.get(RenderSystem.class,field,null));
    }
    void installNeoEvents(Object client) throws Exception {
        if(neoEvents!=null)throw new AssertionError("one native Neo event scope");
        neoEvents=(AutoCloseable)Class.forName("com.naocraftlab.skins.buildlogic.probe.NeoImmediateEvents").getConstructor(Object.class).newInstance(client);
    }
    final Map<String,Object> ownedBindings=new LinkedHashMap<>();
    TextureManager ownedTextures;
    Minecraft ownedMinecraft;
    com.tom.cpm.shared.MinecraftClientAccess ownedHost;
    AutoCloseable auxiliary;
    boolean captured;
    void captureOwned() throws Exception {
        if(captured)return;
        captured=true;
        ownedTextures=PipMaterialProbe.textures==borrowedTextures?null:PipMaterialProbe.textures;
        ownedMinecraft=PipMaterialProbe.minecraft==borrowedMinecraft?null:PipMaterialProbe.minecraft;
        ownedHost=com.tom.cpm.shared.MinecraftClientAccess.get();
        auxiliary=NativeFixtureAbi.auxiliaryScope(borrowedAuxiliary);
        NativeFixtureAbi.captureOwnedSamplers(samplers);
        try(var cleanup=new FixtureCleanup()) {
            for(String field:previous.keySet())cleanup.attempt(()->ownedBindings.put(field,PipMaterialProbe.get(RenderSystem.class,field,null)));
        }
    }
    final Map<String,Object> teardownBindings=new LinkedHashMap<>();
    AutoCloseable lighting,overlay,uiLightmap;
    com.mojang.blaze3d.pipeline.RenderTarget target;
    List<PipCpuBackend.View> closingViews;
    List<PipCpuBackend.Texture> closingTextures;
    List<PipCpuBackend.Buffer> closingBuffers;
    void prepareClose() throws Exception {
        if(closingViews!=null)return;
        closingViews=new ArrayList<>(PipCpuBackend.views);
        closingTextures=new ArrayList<>(PipCpuBackend.textures);
        closingBuffers=new ArrayList<>(PipCpuBackend.buffers);
        closingViews.removeAll(borrowedHardware);closingTextures.removeAll(borrowedHardware);closingBuffers.removeAll(borrowedHardware);
        try(var cleanup=new FixtureCleanup()) {
            cleanup.attempt(this::captureOwned);
            for(String field:previous.keySet())cleanup.attempt(()->teardownBindings.put(field,PipMaterialProbe.get(RenderSystem.class,field,null)));
            cleanup.attempt(()->{if(ownedMinecraft!=null&&ownedMinecraft.gameRenderer!=null)lighting=ownedMinecraft.gameRenderer.lighting();});
            cleanup.attempt(()->{if(ownedMinecraft!=null&&ownedMinecraft.gameRenderer!=null)overlay=ownedMinecraft.gameRenderer.overlayTexture();});
            cleanup.attempt(()->{if(ownedMinecraft!=null&&ownedMinecraft.gameRenderer!=null)uiLightmap=(UiLightmap)PipMaterialProbe.get(GameRenderer.class,"uiLightmap",ownedMinecraft.gameRenderer);});
            cleanup.attempt(()->{if(ownedMinecraft!=null&&ownedMinecraft.gameRenderer!=null)target=ownedMinecraft.gameRenderer.mainRenderTarget();});
        }
    }
    boolean closed;
    public void close()throws Exception{
        if(closed)return;
        closed=true;
        var cleanup=new FixtureCleanup();
        cleanup.attempt(this::prepareClose);
        var textures=ownedTextures;
        var minecraft=ownedMinecraft;
        if(PipMaterialProbe.textures==textures)PipMaterialProbe.textures=null;
        if(PipMaterialProbe.minecraft==minecraft)PipMaterialProbe.minecraft=null;
        var events=neoEvents;neoEvents=null;
        var views=closingViews;
        var hardwareTextures=closingTextures;
        var buffers=closingBuffers;
        try(cleanup) {
            boolean nativeHost=ownedBindings.get("DEVICE")!=previous.get("DEVICE");
            cleanup.attempt(()->{if(nativeHost)PipCpuBackend.signal();});
            cleanup.attempt(()->{if(auxiliary!=null)auxiliary.close();});
            cleanup.attempt(()->{if(textures!=null)textures.close();});
            cleanup.attempt(()->{if(lighting!=null)lighting.close();});
            cleanup.attempt(()->{if(overlay!=null)overlay.close();});
            cleanup.attempt(()->{if(uiLightmap!=null)uiLightmap.close();});
            cleanup.attempt(()->{if(target!=null)target.destroyBuffers();});
            cleanup.attempt(()->NativeFixtureAbi.closeRenderData(previous,ownedBindings));
            for(var view:views)cleanup.attempt(()->{if(!view.isClosed())view.close();});
            for(var texture:hardwareTextures)cleanup.attempt(()->{if(!texture.isClosed())texture.close();});
            for(var buffer:buffers)cleanup.attempt(()->{if(!buffer.isClosed())buffer.close();});
            cleanup.attempt(()->PipMaterialProbe.check(views.stream().allMatch(v->v.closes==1)&&hardwareTextures.stream().allMatch(t->t.closes==1),"all owned CPU hardware texture/view terminals closed once"));
            cleanup.attempt(()->PipMaterialProbe.check(!nativeHost||((Collection<?>)PipMaterialProbe.get(RenderSystem.class,"PENDING_FENCES",null)).isEmpty(),"genuine RenderSystem consumer tasks drained"));
            cleanup.attempt(()->NativeFixtureAbi.restoreSamplers(samplers));
            cleanup.attempt(()->{if(events!=null)events.close();});
            cleanup.attempt(()->{
                if(com.tom.cpm.shared.MinecraftClientAccess.get()==ownedHost) {
                    if(ownedHost!=clientAccess)com.tom.cpm.shared.MinecraftObjectHolder.setClientObject(clientAccess);
                    PipMaterialProbe.check(com.tom.cpm.shared.MinecraftClientAccess.get()==clientAccess,"exact prior CPM host restored");
                }
            });
            for(var entry:previous.entrySet())cleanup.attempt(()->{
                if(teardownBindings.containsKey(entry.getKey())&&PipMaterialProbe.get(RenderSystem.class,entry.getKey(),null)==teardownBindings.get(entry.getKey())) {
                    PipMaterialProbe.set(RenderSystem.class,entry.getKey(),null,entry.getValue());
                    PipMaterialProbe.check(PipMaterialProbe.get(RenderSystem.class,entry.getKey(),null)==entry.getValue(),"exact prior native data binding restored "+entry.getKey());
                }
            });
            cleanup.attempt(()->{
                if(PipMaterialProbe.get(Minecraft.class,"instance",null)==minecraft) {
                    PipMaterialProbe.set(Minecraft.class,"instance",null,client);
                    PipMaterialProbe.check(PipMaterialProbe.get(Minecraft.class,"instance",null)==client,"exact prior client restored");
                }
            });
        }
    }
}
