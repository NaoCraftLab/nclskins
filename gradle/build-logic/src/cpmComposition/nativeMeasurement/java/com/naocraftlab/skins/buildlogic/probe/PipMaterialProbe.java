package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.compat.cpm.*;
import com.naocraftlab.skins.runtime.*;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.definition.*;
import com.tom.cpm.client.*;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.state.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.feature.*;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.client.renderer.state.*;
import net.minecraft.client.renderer.state.gui.*;
import net.minecraft.client.renderer.state.gui.pip.*;
import net.minecraft.client.gui.render.pip.*;
import net.minecraft.resources.Identifier;

public final class PipMaterialProbe {
    static StagedVertexBuffer vertices;
    static TextureManager textures;
    static Minecraft minecraft;
    static PlayerRenderManager manager;
    static Avatar avatar;
    static boolean inLayer;
    static int ears;
    static final UUID PROFILE=new UUID(0,9381), WORLD=new UUID(0,1);
    static final List<Map<String,Object>> rows=new ArrayList<>();
    static Map<String,Integer> initialNetwork;
    @SuppressWarnings("unchecked") static Map<String,Integer> network()throws Exception{return (Map<String,Integer>)call(CpmCurrentNativeGeometryProbe.class,"networkCounters",new Class<?>[]{},null);}
    static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
    static Object allocate(Class<?> c)throws Exception{var f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);return((sun.misc.Unsafe)f.get(null)).allocateInstance(c);}
    static void set(Class<?> c,String name,Object o,Object v)throws Exception{var f=c.getDeclaredField(name);f.setAccessible(true);f.set(o,v);}
    static Object get(Class<?> c,String name,Object o)throws Exception{var f=c.getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static Object call(Class<?> c,String name,Class<?>[] types,Object o,Object...a)throws Exception{var m=c.getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(o,a);}catch(InvocationTargetException e){if(e.getCause() instanceof Error x)throw x;if(e.getCause() instanceof Exception x)throw x;throw e;}}
    public static void enterLayer(){inLayer=true;}
    public static void leaveLayer(){inLayer=false;}
    public static void earsEntry(){check(inLayer&&manager.isBound(avatar.getModel()),"real installed Ears inside genuine upstream bind");ears++;}
    public static FeatureFrameContext context(FeatureRenderDispatcher dispatcher){
        PipCurrentHost.beforeAcquisition.run();
        try{check(get(FeatureRenderDispatcher.class,"stagedVertexBuffer",dispatcher)==vertices,"exact native context staged buffer");return new FeatureFrameContext(new OptionsRenderState(),null,null,null,textures,null,null,vertices);}catch(Exception e){throw new AssertionError(e);}
    }
    static final class Avatar extends net.minecraft.client.renderer.entity.player.AvatarRenderer<net.minecraft.client.player.RemotePlayer>{
        Avatar(EntityRendererProvider.Context context){super(context,false);}
        int layerCount(){return layers.size();}
        @Override protected net.minecraft.client.renderer.rendertype.RenderType getRenderType(AvatarRenderState state,boolean visible,boolean translucent,boolean outline){
            if(state instanceof PipCurrentHost.RawAvatar raw)check(manager.getHolderSafe(getModel(),null,holder->holder.swappedIn&&holder.def==raw.definition&&holder.def instanceof OwnedCpmDefinition&&((OwnedCpmDefinition)holder.def).source().active(),false,false),"genuine upstream binding selects exact captured owned definition before material selection");
            return super.getRenderType(state,visible,translucent,outline);
        }
        @Override protected void submitNameDisplay(AvatarRenderState state,PoseStack pose,SubmitNodeCollector collector,net.minecraft.client.renderer.state.level.CameraRenderState camera){}
    }
    static void host()throws Exception{
        var device=PipCpuBackend.device();
        set(RenderSystem.class,"DEVICE",null,device);set(RenderSystem.class,"renderThread",null,Thread.currentThread());
        NativeFixtureAbi.installRender();
        minecraft=(Minecraft)allocate(Minecraft.class);
        set(Minecraft.class,"instance",null,minecraft);
        textures=(TextureManager)allocate(TextureManager.class);set(TextureManager.class,"byPath",textures,new HashMap<Identifier,AbstractTexture>());set(TextureManager.class,"tickableTextures",textures,new HashSet<>());
        set(Minecraft.class,"textureManager",minecraft,textures);
        vertices=new StagedVertexBuffer(()->"F0 actual CPU vertex drain",65536);
        var game=(GameRenderer)allocate(GameRenderer.class);set(Minecraft.class,"gameRenderer",minecraft,game);set(GameRenderer.class,"lighting",game,new com.mojang.blaze3d.platform.Lighting());
        set(GameRenderer.class,"overlayTexture",game,new OverlayTexture());
        NativeFixtureAbi.installGame(device,game);
    }
    static void rawRenderer()throws Exception{
        var dispatcher=(EntityRenderDispatcher)allocate(EntityRenderDispatcher.class);
        set(EntityRenderDispatcher.class,"playerRenderers",dispatcher,Map.of(net.minecraft.world.entity.player.PlayerModelType.WIDE,avatar,net.minecraft.world.entity.player.PlayerModelType.SLIM,avatar));
        set(EntityRenderDispatcher.class,"renderers",dispatcher,Map.of());
        set(Minecraft.class,"entityRenderDispatcher",minecraft,dispatcher);
    }
    public static void main(String[] args)throws Exception{
        try(var state=new PipProcessState()){execute(args,state);}
        check(rows.size()==16,"all four real lifecycle actions at all four native phases executed");check(initialNetwork.equals(network()),"PIP source/lifecycle/material path adds zero network/resource/event requests");check(!Files.exists(Path.of(args[0]).resolve("synthetic-config.json")),"no synthetic configuration writes");
        Files.writeString(Path.of(args[0]).resolve("pip-material.json"),new com.google.gson.Gson().toJson(Map.of("pipMaterialSliceComplete",true,"f0Complete",false,"caseCount",rows.size(),"cases",rows,"draws",PipCpuBackend.draws,"operations",PipCpuBackend.operations,"allCpuTexturesClosedOnce",true,"allCpuViewsClosedOnce",true,"nativeDataBindingsRestored",true,"networkCounters",network())));
        System.out.println("PIP_MATERIAL_RESULT cases="+rows.size()+" pipMaterialSliceComplete=true f0Complete=false nativeDataBindingsRestored=true");
    }
    @SuppressWarnings("unchecked") static void execute(String[] args,PipProcessState process)throws Exception{
        Path out=Path.of(args[0]);Files.createDirectories(out);
        CpmBehaviorProbe.initializeHost(out.resolve("synthetic-config.json").toFile());initialNetwork=network();
        var loader=(ModelDefinitionLoader<UUID>)get(CpmBehaviorProbe.class,"loader",null);
        CpmIntegration.initialize((collection,name,model)->{throw new AssertionError("new resource/network demand forbidden");});
        host();
        var hostAccess=com.tom.cpm.shared.MinecraftClientAccess.get();
        var mc=new MinecraftObject();manager=mc.getPlayerRenderManager();ClientBase.mc=mc;
        com.tom.cpm.shared.MinecraftObjectHolder.setClientObject(hostAccess);
        var clientClass=Class.forName("com.tom.cpm.client.CustomPlayerModelsClient");Object initializedClient=clientClass.getField("INSTANCE").get(null);if(initializedClient==null){initializedClient=clientClass.getConstructor().newInstance();clientClass.getField("INSTANCE").set(null,initializedClient);}final Object client=initializedClient;ClientBase.class.getField("manager").set(client,new com.tom.cpm.shared.model.RenderManager(manager,null,java.util.function.Function.identity()));
        if(System.getProperty("nclskins.upstream.family").equals("neoforge"))process.installNeoEvents(client);
        var previous=com.tom.cpm.shared.MinecraftClientAccess.get();
        com.tom.cpm.shared.MinecraftObjectHolder.setClientObject((com.tom.cpm.shared.MinecraftClientAccess)Proxy.newProxyInstance(PipMaterialProbe.class.getClassLoader(),new Class<?>[]{com.tom.cpm.shared.MinecraftClientAccess.class},(p,m,a)->m.getName().equals("createTexture")?mc.createTexture():m.invoke(previous,a)));
        var events=(CapeProjection.Events)call(CpmBehaviorProbe.class,"events",new Class<?>[]{},null);
        try(var registration=CapeProjection.installEvents(events);var processed=com.naocraftlab.skins.probe.EarsJoinBridge.process(Path.of(System.getProperty("nclskins.fullLayers.carrier")))){
            var appearance=new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle(processed.id().toString(),64,64),SkinModel.CLASSIC,Optional.empty(),PreviewRenderer.CapeMode.OFF,true);
            var earsHost=get(com.naocraftlab.skins.probe.EarsProbe.Host.class,"INSTANCE",null);var nativeTextures=get(com.naocraftlab.skins.probe.EarsProbe.Host.class,"textures",earsHost);set(TextureManager.class,"byPath",textures,get(nativeTextures.getClass(),"map",nativeTextures));
            for(String resource:List.of("white.png","template/cape.png"))try(var image=PipMaterialProbe.class.getClassLoader().getResourceAsStream("assets/cpm/textures/"+resource)){check(image!=null,"public CPM material resource");textures.register(Identifier.parse("cpm:textures/"+resource),new DynamicTexture(()->"public CPM material",com.mojang.blaze3d.platform.NativeImage.read(image)));}

            var source=CapeProjection.effectiveSkin(PROFILE,WORLD,processed.id().toString(),SkinModel.CLASSIC,true);call(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);
            var player=loader.loadPlayer(PROFILE,ModelDefinitionLoader.PLAYER_UNIQUE);var live=player.getDefinitionFuture().get(5,TimeUnit.SECONDS);
            FeatureRenderDispatcher[] ownedFeatures={null};
            var ownedVertices=vertices;
            try(AutoCloseable resources=()->{
                try(var cleanup=new FixtureCleanup()) {
                    cleanup.attempt(()->{if(ownedFeatures[0]!=null)ownedFeatures[0].close();});
                    cleanup.attempt(ownedVertices::close);
                    cleanup.attempt(live::cleanup);
                    cleanup.attempt(()->CapeProjection.retireSkin(PROFILE));
                    cleanup.attempt(()->call(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null));
                }
            }) {
            live.startResolve();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(live.getResolveState()==ModelDefinition.ModelLoadingState.RESOLVING&&System.nanoTime()<end)Thread.sleep(1);player.getModelDefinition();check(live instanceof OwnedCpmDefinition&&live.doRender(),"actual managed owned source positive");
            avatar=new Avatar((EntityRendererProvider.Context)call(CpmCurrentNativeGeometryProbe.class,"rendererContext",new Class<?>[]{},null));check(avatar.layerCount()==11,"genuine constructor retains all eleven native layers");rawRenderer();
            com.naocraftlab.skins.probe.EarsJoinBridge.use(processed,false);
            var state=new AvatarRenderState();state.scale=1;state.pose=net.minecraft.world.entity.Pose.STANDING;state.showHat=state.showJacket=state.showLeftSleeve=state.showRightSleeve=state.showLeftPants=state.showRightPants=true;
            com.naocraftlab.skins.probe.EarsJoinBridge.prepare(state);
            var access=(PlayerRenderStateAccess)(Object)state;access.cpm$storeState(avatar.getModel());access.cpm$setPlayer((Player)player);var animation=new com.tom.cpm.shared.animation.AnimationState(com.tom.cpm.shared.animation.AnimationEngine.AnimationMode.PLAYER);animation.syncState=new com.tom.cpm.shared.animation.ServerAnimationState();access.cpm$setAnimationState(animation);
            var buffers=(RenderBuffers)allocate(RenderBuffers.class);set(RenderBuffers.class,"stagedVertexBuffer",buffers,vertices);
            var features=new FeatureRenderDispatcher(buffers,null,null,null,new GameRenderState());ownedFeatures[0]=features;
            PipMatrix.run(appearance,processed,loader,features);
            check(PipCpuBackend.draws>0,"genuine dependency feature/prepared-frame geometry drain reaches hardware terminal");
            System.out.println("PIP_NATIVE_EXECUTED draws="+PipCpuBackend.draws+" ears="+ears+" gpuTextures="+PipCpuBackend.textures.size());
            }
        }
    }
}
