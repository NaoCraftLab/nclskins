package com.naocraftlab.skins.buildlogic.probe;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import com.mojang.blaze3d.vertex.*;
import com.tom.cpm.client.*;
import com.tom.cpm.shared.animation.*;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.model.render.*;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;

public final class UpstreamABProbe {
    static final List<Map<String,Object>> rows=new ArrayList<>();
    static PlayerRenderManager manager;
    static ModelDefinition expected;
    static String mode;
    static boolean inLayer;static int earsEntries;static Avatar active;
    public static void enterLayer(){inLayer=true;}
    public static void leaveLayer(){inLayer=false;}
    public static void earsEntry(){check(inLayer&&manager.isBound(active.getModel()),"genuine installed Ears executes while CPM remains bound");earsEntries++;}
    static Object call(Class<?> c,String name,Class<?>[] types,Object receiver,Object... args) throws Exception {
        var m=c.getDeclaredMethod(name,types);m.setAccessible(true);
        try{return m.invoke(receiver,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Error x)throw x;if(e.getCause() instanceof Exception x)throw x;throw e;}
    }
    static Object own(String name,Class<?>[] types,Object... args) throws Exception{return call(CpmCurrentNativeGeometryProbe.class,name,types,null,args);}
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    public static RenderType material(){try{return (RenderType)allocate(RenderType.class);}catch(Exception e){throw new AssertionError(e);}}
    static Object allocate(Class<?> c)throws Exception{return own("allocate",new Class<?>[]{Class.class},c);}
    public static final class Avatar extends net.minecraft.client.renderer.entity.player.AvatarRenderer<net.minecraft.client.player.RemotePlayer> {
        final RenderType material;
        List<Map<String,Object>> anchors;
        Avatar(net.minecraft.client.renderer.entity.EntityRendererProvider.Context context)throws Exception{super(context,false);material=(RenderType)allocate(RenderType.class);}
        public int layerCount(){return layers.size();}
        @Override protected RenderType getRenderType(AvatarRenderState state,boolean visible,boolean translucent,boolean outline){
            try{
                check(manager.isBound(getModel()),"upstream pre bound the native model before BODY");
                var holder=((ModelRenderManager.RedirectRenderer<?>)(Object)getModel().head).getHolder();
                check(holder.def==expected,"same exact definition selected by upstream state binding");
                check(holder.preRenderSetup,"upstream setModelPose reached real pose setup");
                holder.skinBound=true;holder.sheetX=holder.sheetY=64;
                for(var value:RenderMode.values())holder.renderTypes.put(value,new com.tom.cpl.render.VBuffers.NativeRenderType(null,0));
                anchors=anchors(getModel());
                System.out.println("UPSTREAM_BODY_BOUND mode="+mode+" anchors="+anchors);
                return material;
            }catch(Exception e){throw new AssertionError(e);}
        }
        @Override protected void submitNameDisplay(AvatarRenderState state,PoseStack pose,SubmitNodeCollector collector,CameraRenderState camera){}
    }
    static List<Map<String,Object>> anchors(PlayerModel model)throws Exception{
        var values=new ArrayList<Map<String,Object>>();
        for(var part:List.of(model.head,model.body,model.leftArm,model.rightArm,model.leftLeg,model.rightLeg)){
            Object child=call(diy.y2k.five.thermite.EarsLayerRenderer.class,"findCubeBearingChild",new Class<?>[]{net.minecraft.client.model.geom.ModelPart.class},null,part);
            values.add(Map.of("class",part.getClass().getName(),"visible",part.visible,"isEmpty",part.isEmpty(),"nativeCubeChild",child!=null));
        }return values;
    }
    static final class Sink implements VertexConsumer {
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;int vertices;
        public VertexConsumer addVertex(float x,float y,float z){check(Float.isFinite(x)&&Float.isFinite(y)&&Float.isFinite(z),"finite native vertex");min=Math.min(min,y);max=Math.max(max,y);vertices++;return this;}
        public VertexConsumer setColor(int r,int g,int b,int a){return this;}public VertexConsumer setColor(int c){return this;}public VertexConsumer setUv(float u,float v){return this;}public VertexConsumer setUv1(int u,int v){return this;}public VertexConsumer setUv2(int u,int v){return this;}public VertexConsumer setUv3(float u,float v){return this;}public VertexConsumer setNormal(float x,float y,float z){return this;}public VertexConsumer setLineWidth(float w){return this;}
        Map<String,Object> value(){return vertices==0?Map.of("vertices",0):Map.of("vertices",vertices,"minY",min,"maxY",max,"height",max-min,"centerY",(min+max)/2);}
    }
    record Submission(Model<?> model,PoseStack pose,boolean ordinaryFallback){}
    static Map<String,Object> render(ModelDefinition definition,Player<?> player,boolean measurement)throws Exception{
        expected=definition;mode=measurement?"measurement-neutral":"normal-upstream";
        var avatar=new Avatar((net.minecraft.client.renderer.entity.EntityRendererProvider.Context)own("rendererContext",new Class<?>[]{}));
        active=avatar;inLayer=false;earsEntries=0;
        check(avatar.layerCount()==11,"constructor retains all ten vanilla layers plus installed Ears");
        var state=new AvatarRenderState();state.scale=1;state.pose=net.minecraft.world.entity.Pose.STANDING;
        state.showHat=state.showJacket=state.showLeftSleeve=state.showRightSleeve=state.showLeftPants=state.showRightPants=true;
        com.naocraftlab.skins.probe.EarsJoinBridge.prepare(state);
        var access=(PlayerRenderStateAccess)(Object)state;
        access.cpm$storeState(avatar.getModel());
        var animation=new AnimationState(AnimationEngine.AnimationMode.PLAYER);animation.syncState=new ServerAnimationState();
        access.cpm$setPlayer((Player)player);access.cpm$setAnimationState(animation);
        var models=new ArrayList<Submission>();var custom=new ArrayList<java.util.function.Consumer<Sink>>();
        SubmitNodeCollector terminal=(SubmitNodeCollector)Proxy.newProxyInstance(UpstreamABProbe.class.getClassLoader(),new Class<?>[]{SubmitNodeCollector.class},(p,m,a)->{
            if(m.getName().equals("order"))return p;
            if(m.getName().equals("submitModel")){
                if(!inLayer)check(a[0] instanceof CustomModelLayer,"BODY selects genuine deferred CPM model");
                var pose=new PoseStack();pose.last().pose().set(((PoseStack)a[2]).last().pose());pose.last().normal().set(((PoseStack)a[2]).last().normal());models.add(new Submission((Model<?>)a[0],pose,!(a[0] instanceof CustomModelLayer)));return null;
            }
            if(m.getName().equals("submitCustomGeometry")){
                check(manager.isBound(avatar.getModel()),"installed Ears submits inside the upstream binding span");
                com.naocraftlab.skins.probe.EarsJoinBridge.material((RenderType)a[1]);
                var pose=((PoseStack)a[0]).last().copy();var renderer=(SubmitNodeCollector.CustomGeometryRenderer)a[2];custom.add(s->renderer.render(pose,s));return null;
            }throw new AssertionError("unadmitted collector terminal "+m.getName());
        });
        var appearance=CpmCurrentNativeGeometryProbe.class.getDeclaredField("APPEARANCE");appearance.setAccessible(true);
        try(var frame=com.naocraftlab.skins.compat.cpm.CpmFrame.open((com.naocraftlab.skins.client.PreviewRenderer.PreviewAppearance)appearance.get(null))){
            if(measurement){
                manager.bindModel(avatar.getModel(),null,null,definition,player,new AnimationState(AnimationEngine.AnimationMode.PLAYER));
                var holder=((ModelRenderManager.RedirectRenderer<?>)(Object)avatar.getModel().head).getHolder();
                own("neutralSetup",new Class<?>[]{ModelRenderManager.RedirectHolder.class},holder);
                access.cpm$setPlayer(null);
            }
            avatar.submit(state,new PoseStack(),terminal,new CameraRenderState());
            check(!manager.isBound(avatar.getModel()),"genuine upstream post unbinds before frame close");
        }
        check(!manager.isBound(avatar.getModel()),"genuine normal post unbinds before deferred drain");
        check(earsEntries==1,"exactly one genuine installed Ears layer invocation");
        var body=new Sink();var fallback=new Sink();var ears=new Sink();for(var item:models)item.model().renderToBuffer(item.pose(),item.ordinaryFallback()?fallback:body,0,0,-1);for(var item:custom)item.accept(ears);
        check(body.vertices>0,"nonempty genuine native CPM BODY");
        var row=new LinkedHashMap<String,Object>();row.put("mode",mode);row.put("body",body.value());row.put("ordinaryFallback",fallback.value());row.put("earsEntries",earsEntries);row.put("ears",ears.value());row.put("cpmModelSubmissions",models.stream().filter(s->!s.ordinaryFallback()).count());row.put("ordinaryFallbackSubmissions",models.stream().filter(Submission::ordinaryFallback).count());row.put("earsSubmissions",custom.size());row.put("anchors",avatar.anchors);rows.add(row);
        System.out.println("UPSTREAM_AB_ROW "+new com.google.gson.Gson().toJson(row));return row;
    }
    static void setup()throws Exception{
        manager=new PlayerRenderManager();
        var clientClass=Class.forName("com.tom.cpm.client.CustomPlayerModelsClient");
        Object client=clientClass.getField("INSTANCE").get(null);
        if(client==null){client=clientClass.getConstructor().newInstance();clientClass.getField("INSTANCE").set(null,client);}
        var mc=(MinecraftObject)allocate(MinecraftObject.class);var f=MinecraftObject.class.getDeclaredField("prm");f.setAccessible(true);f.set(mc,manager);ClientBase.mc=mc;
        var renderManager=new com.tom.cpm.shared.model.RenderManager(manager,null,java.util.function.Function.identity());
        ClientBase.class.getField("manager").set(client,renderManager);
        if(System.getProperty("nclskins.upstream.family").equals("neoforge")){
            Object bus=Class.forName("net.neoforged.neoforge.common.NeoForge").getField("EVENT_BUS").get(null);Class<?> busType=Class.forName("net.neoforged.bus.api.IEventBus");
            for(String suffix:List.of("Pre","Post")){
                Class<?> eventType=Class.forName("net.neoforged.neoforge.client.event.RenderPlayerEvent$"+suffix);Object target=client;var handler=clientClass.getMethod("playerRender"+suffix,eventType);
                java.util.function.Consumer<Object> consumer=event->{try{handler.invoke(target,event);}catch(Exception e){throw new AssertionError(e);}};
                busType.getMethod("addListener",Class.class,java.util.function.Consumer.class).invoke(bus,eventType,consumer);
            }
            busType.getMethod("start").invoke(bus);
        }
    }
    public static void run(Path out,ModelDefinition live,ModelDefinition neutral,Player<?> player,Object processed)throws Exception{
        var selected=(com.naocraftlab.skins.probe.EarsJoinBridge.Processed)processed;
        var exactFeatures=selected.texture().earsFeatures();
        setup();com.naocraftlab.skins.probe.EarsJoinBridge.use(selected,false);
        try{
            var normal=render(live,player,false);
            String before=(String)call(CpmNeutralGeometryProbe.class,"liveState",new Class<?>[]{ModelDefinition.class},null,live);
            Object future=player.getDefinitionFuture();
            com.naocraftlab.skins.probe.EarsJoinBridge.use((com.naocraftlab.skins.probe.EarsJoinBridge.Processed)processed,true);
            var measurement=render(neutral,player,true);
            check(before.equals(call(CpmNeutralGeometryProbe.class,"liveState",new Class<?>[]{ModelDefinition.class},null,live)),"measurement preserves live numeric pose");
            check(player.getDefinitionFuture()==future&&player.getModelDefinition0()==live,"measurement preserves exact live assignment/future");
            check(normal.get("anchors").equals(measurement.get("anchors")),"normal upstream and neutral measurement retain identical native anchor availability");
            int baseline=(int)normal.get("earsSubmissions"), measured=(int)measurement.get("earsSubmissions");
            check(baseline==measured,"measurement preserves same-carrier installed Ears submissions; zero baseline is allowed");
            own("animate",new Class<?>[]{ModelDefinition.class,long.class},live,500L);
            Object after=own("nativeBody",new Class<?>[]{ModelDefinition.class,Player.class,PoseStack.class},live,player,new PoseStack());
            check(!before.equals(call(CpmNeutralGeometryProbe.class,"liveState",new Class<?>[]{ModelDefinition.class},null,live)),"same managed live animation continues after measurement");
            check(selected.current()&&selected.texture().earsFeatures()==exactFeatures,"same exact processed texture/features survives A/B unchanged");
            System.out.println("UPSTREAM_AB_RESULT baselineEars="+baseline+" measurementEars="+measured+" exactSourceFeaturesUnchanged=true liveAssignmentUnchanged=true liveAnimationContinues=true f0Complete=false");
        }finally{Files.writeString(out.resolve("upstream-ab-result.json"),new com.google.gson.Gson().toJson(Map.of("f0Complete",false,"rows",rows)));}
    }
}
