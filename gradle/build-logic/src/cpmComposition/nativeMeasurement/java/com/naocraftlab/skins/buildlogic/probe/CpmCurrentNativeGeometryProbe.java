package com.naocraftlab.skins.buildlogic.probe;
import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.compat.cpm.*;
import com.naocraftlab.skins.runtime.*;
import com.tom.cpl.util.Image;
import com.tom.cpl.render.VBuffers;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.animation.*;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.definition.*;
import com.tom.cpm.shared.model.*;
import com.tom.cpm.shared.model.render.*;
import com.tom.cpm.client.PlayerRenderManager;
import com.tom.cpm.client.SelfRenderer;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class CpmCurrentNativeGeometryProbe {
    private static final List<Map<String,Object>> ROWS = new ArrayList<>();
    private static Object field(Class<?> owner, String name, Object receiver) throws Exception {
        var f=owner.getDeclaredField(name); f.setAccessible(true); return f.get(receiver);
    }
    private static void set(Class<?> owner,String name,Object receiver,Object value) throws Exception {
        var f=owner.getDeclaredField(name); f.setAccessible(true); f.set(receiver,value);
    }
    private static Object invoke(Class<?> owner,String name,Class<?>[] types,Object receiver,Object... args) throws Exception {
        var m=owner.getDeclaredMethod(name,types); m.setAccessible(true);
        try { return m.invoke(receiver,args); } catch(InvocationTargetException e) {
            if(e.getCause() instanceof Error error)throw error;
            if(e.getCause() instanceof Exception error)throw error;
            throw e;
        }
    }
    private static Image fixture(boolean decor,boolean equipment,boolean jump,boolean small,float scale,float translation) throws Exception {
        var f=Class.forName(CpmNeutralGeometryProbe.class.getName()+"$Fixture");
        var c=f.getDeclaredConstructor(boolean.class,boolean.class,boolean.class,boolean.class,float.class,float.class);c.setAccessible(true);
        return (Image)invoke(CpmNeutralGeometryProbe.class,"fixture",new Class<?>[]{f},null,c.newInstance(decor,equipment,jump,small,scale,translation));
    }
    private static ModelDefinitionLoader<UUID> loader;
    private static final UUID PROFILE=new UUID(0,9381);
    private static final UUID WORLD=new UUID(0,1);
    private static String LOCATION="fixture:f0-native-cpm";
    private static PreviewRenderer.PreviewAppearance APPEARANCE=new PreviewRenderer.PreviewAppearance(
        new TextureRegistry.TextureHandle(LOCATION,64,64),SkinModel.CLASSIC,Optional.empty(),PreviewRenderer.CapeMode.OFF,true);
    private static final class Sink implements VertexConsumer {
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY; int vertices;
        double height(){check(vertices>0 && max>min,"nonempty native geometry");return max-min;}
        double center(){return (max+min)/2;}
        public VertexConsumer addVertex(float x,float y,float z){check(Float.isFinite(x)&&Float.isFinite(y)&&Float.isFinite(z),"finite native geometry");min=Math.min(min,y);max=Math.max(max,y);vertices++;return this;}
        public VertexConsumer setColor(int r,int g,int b,int a){return this;}
        public VertexConsumer setColor(int c){return this;}
        public VertexConsumer setUv(float u,float v){return this;}
        public VertexConsumer setUv1(int u,int v){return this;}
        public VertexConsumer setUv2(int u,int v){return this;}
        public VertexConsumer setUv3(float u,float v){return this;}
        public VertexConsumer setNormal(float x,float y,float z){return this;}
        public VertexConsumer setLineWidth(float w){return this;}
    }
    private static PlayerModel neutralModel() {
        return new PlayerModel(LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE,false),64,64).bakeRoot(),false);
    }
    public static final class CpuAvatar extends net.minecraft.client.renderer.entity.player.AvatarRenderer<net.minecraft.client.player.RemotePlayer> {
        private net.minecraft.client.renderer.rendertype.RenderType terminalMaterial;
        private CpuAvatar(net.minecraft.client.renderer.entity.EntityRendererProvider.Context context) { super(context,false); }
        public List<?> installedLayers() { return List.copyOf(layers); }
        @Override protected net.minecraft.client.renderer.rendertype.RenderType getRenderType(net.minecraft.client.renderer.entity.state.AvatarRenderState state,boolean visible,boolean translucent,boolean outline){return terminalMaterial;}
        @Override protected void submitNameDisplay(net.minecraft.client.renderer.entity.state.AvatarRenderState state,PoseStack pose,net.minecraft.client.renderer.SubmitNodeCollector collector,net.minecraft.client.renderer.state.level.CameraRenderState camera){}
    }
    private static Object allocate(Class<?> type) throws Exception {
        var f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);
        return ((sun.misc.Unsafe)f.get(null)).allocateInstance(type);
    }
    private static void neutralSetup(ModelRenderManager.RedirectHolder<?,?,?,?> holder) throws Exception {
        var owner=holder.playerObj;
        holder.playerObj=null;
        try {invoke(ModelRenderManager.RedirectHolder.class,"bindFirstSetup",new Class<?>[]{boolean.class},holder,true);}
        finally {holder.playerObj=owner;}
        holder.preRenderSetup=true;holder.skinBound=true;holder.sheetX=holder.sheetY=64;
        for(var mode:RenderMode.values())holder.renderTypes.put(mode,new VBuffers.NativeRenderType(null,0));
    }
    public static net.minecraft.client.renderer.texture.TextureAtlas armorAtlas(net.minecraft.client.resources.model.sprite.AtlasManager atlas,net.minecraft.resources.Identifier id){
        check(atlas==null && id.equals(NativeFixtureAbi.armorAtlas()),"constructor requests exact readonly armor-trim atlas terminal");return null;
    }
    private static net.minecraft.client.model.geom.EntityModelSet installedModelSet() {
        var roots=com.google.common.collect.ImmutableMap.<net.minecraft.client.model.geom.ModelLayerLocation,LayerDefinition>builder();
        var armor=PlayerModel.createArmorMeshSet(new CubeDeformation(0.5F),new CubeDeformation(1)).map(mesh->LayerDefinition.create(mesh,64,32));
        net.minecraft.client.model.geom.ModelLayers.PLAYER_ARMOR.putFrom(armor,roots);
        roots.put(net.minecraft.client.model.geom.ModelLayers.PLAYER,LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE,false),64,64));
        roots.put(net.minecraft.client.model.geom.ModelLayers.ARROW,net.minecraft.client.model.object.projectile.ArrowModel.createBodyLayer());
        roots.put(net.minecraft.client.model.geom.ModelLayers.BEE_STINGER,net.minecraft.client.model.animal.bee.BeeStingerModel.createBodyLayer());
        roots.put(net.minecraft.client.model.geom.ModelLayers.PLAYER_EARS,net.minecraft.client.model.player.PlayerEarsModel.createEarsLayer());
        roots.put(net.minecraft.client.model.geom.ModelLayers.PLAYER_CAPE,net.minecraft.client.model.player.PlayerCapeModel.createCapeLayer());
        var elytra=net.minecraft.client.model.object.equipment.ElytraModel.createLayer();
        roots.put(net.minecraft.client.model.geom.ModelLayers.ELYTRA,elytra);
        roots.put(net.minecraft.client.model.geom.ModelLayers.ELYTRA_BABY,elytra.apply(net.minecraft.client.model.object.equipment.ElytraModel.BABY_TRANSFORMER));
        roots.put(net.minecraft.client.model.geom.ModelLayers.PARROT,net.minecraft.client.model.animal.parrot.ParrotModel.createBodyLayer());
        roots.put(net.minecraft.client.model.geom.ModelLayers.PLAYER_SPIN_ATTACK,net.minecraft.client.model.effects.SpinAttackEffectModel.createLayer());
        return new net.minecraft.client.model.geom.EntityModelSet(roots.buildOrThrow());
    }
    private static net.minecraft.client.renderer.entity.EntityRendererProvider.Context rendererContext() throws Exception {
        return NativeFixtureAbi.context(installedModelSet());
    }
    private static Sink nativeEntity(ModelDefinition definition,Player<?> player,PoseStack pose) throws Exception {
        return nativeEntity(definition,player,pose,()->{});
    }
    private static Sink nativeEntity(ModelDefinition definition,Player<?> player,PoseStack pose,Runnable afterRecord) throws Exception {
        var manager=new PlayerRenderManager();
        var avatar=new CpuAvatar(rendererContext());
        var model=avatar.getModel();
        check(avatar.installedLayers().size()==(Boolean.getBoolean("nclskins.fullLayers.ears")?11:10),"genuine AvatarRenderer constructor installs all ordinary and injected layers");
        System.out.println("INSTALLED_LAYERS "+avatar.installedLayers().stream().map(v->v.getClass().getName()).toList());
        avatar.terminalMaterial=(net.minecraft.client.renderer.rendertype.RenderType)allocate(net.minecraft.client.renderer.rendertype.RenderType.class);
        List<Submitted> queue=new ArrayList<>();
        List<java.util.function.Consumer<Sink>> custom=new ArrayList<>();
        var terminal=(net.minecraft.client.renderer.SubmitNodeCollector)Proxy.newProxyInstance(CpmCurrentNativeGeometryProbe.class.getClassLoader(),new Class<?>[]{net.minecraft.client.renderer.SubmitNodeCollector.class},(p,m,a)->{
            if(m.getName().equals("order"))return p;
            if(m.getName().equals("submitCustomGeometry")){
                com.naocraftlab.skins.probe.EarsJoinBridge.material((net.minecraft.client.renderer.rendertype.RenderType)a[1]);
                var snapshot=((PoseStack)a[0]).last().copy();
                var callback=(net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer)a[2];
                custom.add(sink->callback.render(snapshot,sink));return null;
            }
            if(m.getName().equals("submitModel")){
                if(definition!=null)check(a[0] instanceof com.tom.cpm.client.CustomModelLayer,"native entity selected genuine CPM deferred layer");
                var copy=new PoseStack();copy.last().pose().set(((PoseStack)a[2]).last().pose());copy.last().normal().set(((PoseStack)a[2]).last().normal());
                queue.add(new Submitted((Model<?>)a[0],copy));return null;
            }
            throw new AssertionError("Unexpected native entity terminal "+m.getName());
        });
        var state=new net.minecraft.client.renderer.entity.state.AvatarRenderState();
        state.scale=1;state.pose=net.minecraft.world.entity.Pose.STANDING;
        state.showHat=state.showJacket=state.showLeftSleeve=state.showRightSleeve=state.showLeftPants=state.showRightPants=true;
        com.naocraftlab.skins.probe.EarsJoinBridge.prepare(state);
        try(var frame=CpmFrame.open(APPEARANCE)){
            if(definition!=null){
                manager.bindModel(model,null,null,definition,player,new AnimationState(AnimationEngine.AnimationMode.PLAYER));
                check(manager.isBound(model),"native entity CPM manager admits selected owned definition");
                var holder=((ModelRenderManager.RedirectRenderer<?>)(Object)model.head).getHolder();neutralSetup(holder);
            }
            avatar.submit(state,pose,new com.tom.cpm.client.CPMOrderedSubmitNodeCollector.CPMSubmitNodeCollector(terminal),new net.minecraft.client.renderer.state.level.CameraRenderState());
            var anchors=List.of(model.head,model.body,model.leftArm,model.rightArm,model.leftLeg,model.rightLeg).stream().map(part->part.visible).toList();
            System.out.println("NATIVE_ANCHORS visible="+anchors+" cpm="+(definition!=null));
            if(Boolean.getBoolean("nclskins.fullLayers.ears")){
                List<String> cubeAnchors=new ArrayList<>();
                for(var part:List.of(model.head,model.body,model.leftArm,model.rightArm,model.leftLeg,model.rightLeg)){
                    var child=invoke(diy.y2k.five.thermite.EarsLayerRenderer.class,"findCubeBearingChild",new Class<?>[]{net.minecraft.client.model.geom.ModelPart.class},null,part);
                    cubeAnchors.add("empty="+part.isEmpty()+",nativeCubeChild="+(child!=null));
                }
                System.out.println("NATIVE_EARS_CUBE_ANCHORS "+cubeAnchors);
            }
            check(!queue.isEmpty(),"native model record precedes retirement control");afterRecord.run();
        }
        invoke(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);
        check(!manager.isBound(model),"actual owned frame restores native binding before queued model consumption");
        check(!queue.isEmpty(),"actual LivingEntityRenderer.submit produces deferred native body");
        var sink=new Sink();List<Integer> emitted=new ArrayList<>();
        for(var item:queue){int before=sink.vertices;item.model().renderToBuffer(item.pose(),sink,0,0,-1);emitted.add(sink.vertices-before);}
        for(var callback:custom)callback.accept(sink);sink.height();
        System.out.println("NATIVE_DRAIN models="+queue.size()+" custom="+custom.size()+" vertices="+sink.vertices+" perModel="+emitted);return sink;
    }
    private record Submitted(Model<?> model,PoseStack pose) {}
    static Sink nativeBody(ModelDefinition definition,Player<?> player,PoseStack pose) throws Exception {
        var model=new PlayerModel(LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE,false),64,64).bakeRoot(),false);
        var manager=new PlayerRenderManager();
        List<Submitted> queue=new ArrayList<>();
        var collector=(OrderedSubmitNodeCollector)Proxy.newProxyInstance(CpmCurrentNativeGeometryProbe.class.getClassLoader(),new Class<?>[]{OrderedSubmitNodeCollector.class},(p,m,a)->{
            if(m.getName().equals("submitModel")) {
                check(a[0] instanceof com.tom.cpm.client.CustomModelLayer,"actual native CustomModelLayer producer, not builtin or ordinary fallback");
                var copy=new PoseStack(); copy.last().pose().set(((PoseStack)a[2]).last().pose());copy.last().normal().set(((PoseStack)a[2]).last().normal());
                queue.add(new Submitted((Model<?>)a[0],copy));return null;
            }
            throw new AssertionError("Unexpected native collector terminal "+m.getName());
        });
        try(var frame=CpmFrame.open(APPEARANCE)) {
            manager.bindModel(model,null,null,definition,player,new AnimationState(AnimationEngine.AnimationMode.PLAYER));
            check(manager.isBound(model),"genuine native holder bound");
            var holder=((ModelRenderManager.RedirectRenderer<?>)(Object)model.head).getHolder();
            check(holder.def==definition && holder.playerObj==player && holder.swappedIn,"exact native binding identity");
            neutralSetup(holder);
            check((Object)model.root() instanceof SelfRenderer,"native root swapped by dependency manager");
            ((SelfRenderer)(Object)model.root()).submitSelf(new SelfRenderer.RenderCollector(pose,collector,null,0,0,-1,0,null,1,null));
            check(!queue.isEmpty(),"genuine native deferred model queue is nonempty");
        }
        check(!manager.isBound(model),"production CpmFrame releases binding before deferred consumption");
        var sink=new Sink();
        for(var item:queue)item.model().renderToBuffer(item.pose(),sink,0,0,-1);
        sink.height(); return sink;
    }
    private record NumericInput(double height,double center,double referenceHeight,double referenceCenter,java.util.function.BooleanSupplier current){
        double factor(){return Math.min(1,referenceHeight/height);}
    }
    private static NumericInput numeric;
    private static String sensitivity="";
    public static float layoutPadding(){check(numeric!=null && numeric.current().getAsBoolean(),"numeric prototype remains exact-source current");return 0.97F*(sensitivity.equals("omit-normalization")?1:(float)numeric.factor());}
    public static CenteredPlayerPreviewGeometry.EntityTranslation centerTranslation(float originalHeight,float pitch){
        check(originalHeight==CenteredPlayerPreviewGeometry.STANDING_PLAYER_HEIGHT,"exact inspected standing-center input");
        check(numeric!=null && numeric.current().getAsBoolean(),"numeric prototype center remains exact-source current");
        if(!sensitivity.equals("reuse-reference-center"))return CenteredPlayerPreviewGeometry.centeredEntityTranslation(originalHeight,pitch);
        double ordinaryOffset=CenteredPlayerPreviewGeometry.modernEntityTranslationY(originalHeight)-numeric.referenceCenter();
        double center=numeric.center()+ordinaryOffset/numeric.factor();
        return new CenteredPlayerPreviewGeometry.EntityTranslation((float)(center*Math.cos(pitch)),(float)(-center*Math.sin(pitch)));
    }
    private static void injectOperands(org.objectweb.asm.tree.ClassNode node){
        var render=node.methods.stream().filter(m->m.name.equals("render") && m.desc.endsWith("PreviewRenderer$PreviewRequest;)V")).findFirst().orElseThrow();
        int scales=0,centers=0;String own=org.objectweb.asm.Type.getInternalName(CpmCurrentNativeGeometryProbe.class);
        for(var instruction:render.instructions.toArray()){
            if(instruction instanceof org.objectweb.asm.tree.LdcInsnNode constant && constant.cst.equals(0.97F)){
                render.instructions.set(constant,new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC,own,"layoutPadding","()F",false));scales++;
            }
            if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call && call.owner.equals("com/naocraftlab/skins/client/CenteredPlayerPreviewGeometry") && call.name.equals("centeredEntityTranslation") && call.desc.equals("(FF)Lcom/naocraftlab/skins/client/CenteredPlayerPreviewGeometry$EntityTranslation;")){
                call.owner=own;call.name="centerTranslation";centers++;
            }
        }
        check(scales==1 && centers==1,"one exact compiled scale/center operand each");
    }
    public static final class GeometryDispatcher {
        static ModelDefinition definition;static Player<?> player;static Sink consumed;
        public void submit(CpmExtractionBodyProbe.State state,CpmPreviewScopeProbe.Camera camera,double x,double y,double z,PoseStack pose,net.minecraft.client.renderer.SubmitNodeCollector collector){
            check(state instanceof CpmExtractionBodyProbe.Avatar && ((CpmExtractionBodyProbe.Avatar)state).appearance==APPEARANCE,"actual GuiEntityRenderer forwards selected appearance to native consumer");
            try{
                Object context=state.getClass().getMethod("nclskins$previewContext").invoke(state);
                check((boolean)context.getClass().getMethod("current").invoke(context),"compiled CPM preparation/source context current before native acquisition");
                consumed=nativeEntity(definition,player,pose);
                check((boolean)context.getClass().getMethod("current").invoke(context),"compiled CPM preparation/source context survives deferred native consumption");
            }catch(Exception e){throw new AssertionError(e);}
        }
    }
    private static Sink consumeGui(ClassLoader bridge,CpmExtractionBodyProbe.GuiState queued,ModelDefinition def,Player<?> player) throws Exception{
        String name="net.minecraft.client.gui.render.pip.GuiEntityRenderer";
        var types=CpmExtractionBodyProbe.nativeTypes();
        types.remove("com/mojang/blaze3d/vertex/PoseStack");
        types.remove("net/minecraft/client/renderer/SubmitNodeCollector");
        types.put("net/minecraft/client/renderer/entity/EntityRenderDispatcher",GeometryDispatcher.class);
        var node=new org.objectweb.asm.tree.ClassNode();
        byte[] original;
        try(var in=bridge.getResourceAsStream(name.replace('.','/')+".class")){original=in.readAllBytes();new org.objectweb.asm.ClassReader(original).accept(node,0);}
        int bodies=0;
        for(var method:node.methods){
            if(method.name.equals("renderToTexture") && method.desc.equals("(Lnet/minecraft/client/renderer/state/gui/pip/GuiEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V")){
                method.name="nclskins$nativeRenderToTexture";bodies++;
            }
            for(var instruction:method.instructions.toArray())if(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call && call.owner.equals("net/minecraft/client/renderer/state/gui/pip/GuiEntityRenderState")){
                call.desc=call.desc.replace("Lorg/joml/Vector3fc;","Lorg/joml/Vector3f;").replace("Lorg/joml/Quaternionfc;","Lorg/joml/Quaternionf;");
            }
        }
        check(bodies==1,"one exact SDK GUI consumer body");
        var writer=new org.objectweb.asm.ClassWriter(0);
        node.accept(new org.objectweb.asm.commons.ClassRemapper(writer,new org.objectweb.asm.commons.Remapper(org.objectweb.asm.Opcodes.ASM9){
            @Override public String map(String internalName){var mapped=types.get(internalName);return mapped==null?internalName:org.objectweb.asm.Type.getInternalName(mapped);}
        }));
        byte[] bytes=writer.toByteArray();
        var consumerLoader=new ClassLoader(bridge){Class<?> selected(){return defineClass(name,bytes,0,bytes.length);}};
        var guiType=consumerLoader.selected();
        String export=System.getProperty("nclskins.cpm.extractionExport");
        if(export!=null){var path=Path.of(export).resolve(name.replace('.','/')+".class");Files.createDirectories(path.getParent());Files.write(path,bytes);}
        System.out.println("GUI_NATIVE_BODY original="+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original))+" bound="+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        Object gui=guiType.getConstructor(GeometryDispatcher.class).newInstance(new GeometryDispatcher());
        var method=guiType.getDeclaredMethod("nclskins$nativeRenderToTexture",CpmExtractionBodyProbe.GuiState.class,PoseStack.class,net.minecraft.client.renderer.SubmitNodeCollector.class);method.setAccessible(true);
        var pose=new PoseStack();pose.scale(queued.scale(),queued.scale(),queued.scale());
        GeometryDispatcher.definition=def;GeometryDispatcher.player=player;GeometryDispatcher.consumed=null;
        try{method.invoke(gui,queued,pose,null);check(GeometryDispatcher.consumed!=null,"actual SDK GuiEntityRenderer reaches deferred native model consumer");return GeometryDispatcher.consumed;}
        catch(InvocationTargetException e){if(e.getCause() instanceof Error error)throw error;throw e;}
        finally{GeometryDispatcher.definition=null;GeometryDispatcher.player=null;}
    }
    private static java.util.function.BiConsumer<ClassLoader,CpmExtractionBodyProbe.GuiState> queuedConsumer=(bridge,state)->{};
    private static CpmExtractionBodyProbe.GuiState currentOperands() throws Exception {
        String prefix="com.naocraftlab.skins.compat.client.identifier.extraction.";
        var loaderType=Class.forName(CpmExtractionBodyProbe.class.getName()+"$Loader");
        var ctor=loaderType.getDeclaredConstructor(Map.class);ctor.setAccessible(true);
        var bridge=(ClassLoader)ctor.newInstance(CpmExtractionBodyProbe.nativeTypes());
        var marker=bridge.loadClass(prefix+"NclSkinsWideDepthState");
        var contextType=bridge.loadClass(prefix+"AvatarPreviewContext");
        var stateType=(Class<?>)invoke(loaderType,"state",new Class<?>[]{Class.class},bridge,marker);
        var previous=CpmExtractionBodyProbe.Client.current;
        Object previousFactory=field(CpmExtractionBodyProbe.class,"stateFactory",null);
        var client=new CpmExtractionBodyProbe.Client();client.player=new CpmPreviewScopeProbe.Avatar(client.level);
        CpmExtractionBodyProbe.Client.current=client;
        try(var nativeProfile=CpmBehaviorProbe.nativeProfileScope()) {
            var type=bridge.loadClass(prefix+"AvatarRenderStatePreviewRenderer");
            Object renderer=invoke(CpmExtractionBodyProbe.class,"create",new Class<?>[]{Class.class},null,type);
            var session=new EditorPreviewSession();
            set(type,"session",renderer,session);
            set(type,"previewPlayer",renderer,client.player);set(type,"previewLevel",renderer,client.level);
            set(type,"previewProfile",renderer,new com.mojang.authlib.GameProfile(PROFILE,"SyntheticNative"));
            set(type,"previewClock",renderer,new EditorPreviewClock());
            set(type,"bakedRenderer",renderer,new CpmExtractionBodyProbe.Baked());
            set(type,"diagnostics",renderer,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
            set(type,"accessoryObserver",renderer,(java.util.function.Consumer<PreviewAccessories>)ignored->{});
            set(type,"bodyObserver",renderer,(java.util.function.Consumer<PreviewRenderer.BodyStatus>)ignored->{});
            java.util.function.Function<Object,CpmExtractionBodyProbe.Avatar> factory=context->{
                try {
                    var state=(CpmExtractionBodyProbe.Avatar)stateType.getConstructor().newInstance();
                    marker.getMethod("nclskins$setPreviewContext",contextType).invoke(state,context);
                    state.appearance=APPEARANCE;return state;
                } catch(Exception e){throw new AssertionError(e);}
            };
            set(CpmExtractionBodyProbe.class,"stateFactory",null,factory);
            type.getMethod("prepareBody",String.class,boolean.class).invoke(renderer,"fixture:current-native-numeric",true);
            var request=new PreviewRenderer.PreviewRequest(APPEARANCE,0,0,160,180,0,0,1,PreviewRenderer.PreviewIntent.EDITOR_DRAFT);
            CpmExtractionBodyProbe.Graphics.queued=null;
            type.getMethod("render",CpmExtractionBodyProbe.Graphics.class,PreviewRenderer.PreviewRequest.class).invoke(renderer,new CpmExtractionBodyProbe.Graphics(),request);
            var queued=CpmExtractionBodyProbe.Graphics.queued;
            check(queued!=null,"compiled current extraction renderer enqueues actual own appearance");
            check(((CpmExtractionBodyProbe.Avatar)queued.renderState()).appearance==APPEARANCE,"compiled current renderer preserves selected appearance identity");
            queuedConsumer.accept(bridge,queued);
            return queued;
        } finally {
            set(CpmExtractionBodyProbe.class,"stateFactory",null,previousFactory);
            CpmExtractionBodyProbe.Client.current=previous;
        }
    }
    private static Sink reference(){
        var root=LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE,false),64,64).bakeRoot();
        var sink=new Sink();root.render(new PoseStack(),sink,0,0);sink.height();return sink;
    }
    private static void animate(ModelDefinition def,long time) throws Exception {
        invoke(CpmNeutralGeometryProbe.class,"animate",new Class<?>[]{ModelDefinition.class,long.class},null,def,time);
    }
    private static Map<String,Integer> networkCounters() throws Exception {
        var values=new TreeMap<String,Integer>();
        for(String name:List.of("AUXILIARY","RESOURCE"))values.put(name,((java.util.concurrent.atomic.AtomicInteger)field(CpmBehaviorProbe.class,name,null)).get());
        Object net=field(CpmBehaviorProbe.class,"NET",null);
        for(String name:List.of("world","preview","events"))values.put(name,((java.util.concurrent.atomic.AtomicInteger)field(net.getClass(),name,net)).get());
        return values;
    }
    private static void await(ModelDefinition def) throws Exception {
        def.startResolve();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(def.getResolveState()==ModelDefinition.ModelLoadingState.RESOLVING && System.nanoTime()<deadline)Thread.sleep(1);
        check(def.getError()==null && def.doRender(),"managed owned definition resolves successfully");
    }
    private static void controls() throws Exception {
        record Case(String name,boolean decor,boolean equipment,boolean small,float scale,float translation){}
        for(var c:List.of(new Case("tall",true,false,false,1,0),new Case("small",false,false,true,1,0),
                new Case("translated",true,false,false,1,32),new Case("native-scale-half",true,false,false,0.5F,0),
                new Case("native-scale-double",true,false,false,2,32),new Case("no-body-decoration",false,false,false,1,0),
                new Case("native-equipment-present",true,true,false,1,0))){
            var image=fixture(c.decor(),c.equipment(),false,c.small(),c.scale(),c.translation());Object texture=new Object();
            CapeProjection.skinTextureReady(LOCATION,image.getData(),texture);
            var source=CapeProjection.effectiveSkin(PROFILE,WORLD,LOCATION,SkinModel.CLASSIC,true);invoke(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);
            var player=loader.loadPlayer(PROFILE,ModelDefinitionLoader.PLAYER_UNIQUE);
            var def=player.getDefinitionFuture().get(5,TimeUnit.SECONDS);await(def);player.getModelDefinition();
            try{
                check(def instanceof OwnedCpmDefinition && ((OwnedCpmDefinition)def).source()==source,"matrix exact managed source "+c.name());
                var admitted=CpmIntegration.preparePreview(PROFILE,APPEARANCE);check(admitted.status()==PreviewRenderer.BodyStatus.READY && admitted.current().getAsBoolean(),"matrix policy/currentness admission "+c.name());
                var raw=nativeBody(def,player,new PoseStack());double rawExpected=(c.small()?0.875:c.decor()?4.5:3)*c.scale();
                near(raw.height(),rawExpected,"native authored scale appears exactly once "+c.name());
                var reference=nativeEntity(null,player,new PoseStack());var body=nativeEntity(def,player,new PoseStack());row(c.name()+"-entity-neutral",body);
                near(body.height(),raw.height()*0.9375,"same actual AvatarRenderer native scale in both producers "+c.name());
                double n=Math.min(1,reference.height()/body.height());var pose=new PoseStack();pose.scale((float)n,(float)n,(float)n);
                var consumed=nativeEntity(def,player,pose);row(c.name()+"-entity-normalized-control",consumed);
                near(consumed.height(),Math.min(reference.height(),body.height()),"native height normalization "+c.name());near(consumed.center(),body.center()*n,"native authored center "+c.name());
                if(c.small())near(n,1,"small native body is not enlarged");
                check(admitted.current().getAsBoolean(),"measurement does not retire source");
                if(c.name().equals("tall")){
                    Player.setEnableRendering(false);
                    try{check(CpmIntegration.preparePreview(PROFILE,APPEARANCE).status()==PreviewRenderer.BodyStatus.BLOCKED,"actual disabled-policy consumer refuses full readiness");}
                    finally{Player.setEnableRendering(true);}
                    check(CpmIntegration.preparePreview(PROFILE,APPEARANCE).status()==PreviewRenderer.BodyStatus.READY,"actual policy re-enable positive");
                    var retired=loader.loadModel(new Image(source.pixels(),64),player);retired.resolveAll();retired.cleanup();
                    invoke(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);
                    var model=neutralModel();var manager=new PlayerRenderManager();
                    try(var frame=CpmFrame.open(APPEARANCE)){manager.bindModel(model,null,null,retired,player,new AnimationState(AnimationEngine.AnimationMode.PLAYER));check(!manager.isBound(model),"release before native acquisition rejects old definition");}
                    var held=loader.loadModel(new Image(source.pixels(),64),player);held.resolveAll();
                    var after=nativeEntity(held,player,new PoseStack(),held::cleanup);row("release-after-record-native-consumer",after);
                    near(after.height(),body.height(),"actual deferred record survives definition release after acquisition");
                    check(held.getResolveState()==ModelDefinition.ModelLoadingState.CLEANED_UP,"held native source logically retires");
                }
                CapeProjection.retireSkin(PROFILE);check(!admitted.current().getAsBoolean(),"source retirement revokes readiness after actual native pass");
                var stale=new PlayerRenderManager();var staleModel=neutralModel();
                try(var frame=CpmFrame.open(APPEARANCE)){stale.bindModel(staleModel,null,null,def,player,new AnimationState(AnimationEngine.AnimationMode.PLAYER));check(!stale.isBound(staleModel),"retired source cannot reacquire native binding");}
            }finally{def.cleanup();CapeProjection.retireSkin(PROFILE);invoke(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);java.lang.ref.Reference.reachabilityFence(texture);}
        }
    }
    private static void row(String name,Sink sink){
        ROWS.add(Map.of("case",name,"height",sink.height(),"centerY",sink.center(),"vertices",sink.vertices));
        System.out.println("NATIVE_CPM "+name+" height="+sink.height()+" center="+sink.center()+" vertices="+sink.vertices);
    }
    private static void near(double actual,double expected,String message){check(Math.abs(actual-expected)<0.0001,message+" expected="+expected+" actual="+actual);}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    @SuppressWarnings("unchecked") public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);
        CpmBehaviorProbe.initializeHost(out.resolve("synthetic-config.json").toFile());
        loader=(ModelDefinitionLoader<UUID>)field(CpmBehaviorProbe.class,"loader",null);
        set(CpmNeutralGeometryProbe.class,"config",null,field(CpmBehaviorProbe.class,"config",null));
        set(CpmNeutralGeometryProbe.class,"tags",null,field(CpmBehaviorProbe.class,"tags",null));
        CpmIntegration.initialize((collection,name,model)->{throw new AssertionError("new resource/network demand forbidden");});
        var events=(CapeProjection.Events)invoke(CpmBehaviorProbe.class,"events",new Class<?>[]{},null);
        var networkBefore=networkCounters();
        com.naocraftlab.skins.probe.EarsJoinBridge.Processed processed=null;
        if(System.getProperty("nclskins.fullLayers.carrier")!=null){
            processed=com.naocraftlab.skins.probe.EarsJoinBridge.process(Path.of(System.getProperty("nclskins.fullLayers.carrier")));
            LOCATION=processed.id().toString();APPEARANCE=new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle(LOCATION,64,64),SkinModel.CLASSIC,Optional.empty(),PreviewRenderer.CapeMode.OFF,true);
        }
        try(var registration=CapeProjection.installEvents(events)){
            Image image=processed==null?fixture(true,true,true,false,1,32):new Image(processed.pixels(),64);
            Object texture=processed==null?new Object():processed.texture();
            CapeProjection.skinTextureReady(LOCATION,image.getData(),texture);
            var source=CapeProjection.effectiveSkin(PROFILE,WORLD,LOCATION,SkinModel.CLASSIC,true);
            invoke(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);
            var player=loader.loadPlayer(PROFILE,ModelDefinitionLoader.PLAYER_UNIQUE);
            check(CpmIntegration.source(player)==source,"actual managed player selected owned source");
            var live=player.getDefinitionFuture().get(5,TimeUnit.SECONDS);
            check(live instanceof OwnedCpmDefinition && ((OwnedCpmDefinition)live).source()==source,"transformed importer selected OwnedCpmDefinition");
            live.startResolve();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(live.getResolveState()==ModelDefinition.ModelLoadingState.RESOLVING && System.nanoTime()<deadline)Thread.sleep(1);
            player.getModelDefinition();
            check(live.doRender(),"managed live definition resolves");
            var future=player.getDefinitionFuture();
            animate(live,0);
            var first=nativeBody(live,player,new PoseStack());row("live-initial-jump",first);
            String liveState=(String)invoke(CpmNeutralGeometryProbe.class,"liveState",new Class<?>[]{ModelDefinition.class},null,live);
            var neutral=loader.loadModel(new Image(source.pixels(),64),player);
            check(neutral instanceof OwnedCpmDefinition && neutral!=live && ((OwnedCpmDefinition)neutral).source()==source,"neutral definition comes from exact same selected owned pixels and source");
            neutral.resolveAll();
            try {
            if(args.length>1 && args[1].equals("upstream-ab")){
                invoke(Class.forName("com.naocraftlab.skins.buildlogic.probe.UpstreamABProbe"),"run",new Class<?>[]{Path.class,ModelDefinition.class,ModelDefinition.class,Player.class,Object.class},null,out,live,neutral,player,processed);
                check(networkBefore.equals(networkCounters()),"upstream A/B adds no resource/network work");
                check(!Files.exists(out.resolve("synthetic-config.json")),"upstream A/B does not write configuration");
                return;
            }
            var measured=nativeBody(neutral,player,new PoseStack());row("selected-owned-neutral",measured);
            check(Math.abs(first.center()-measured.center())>2,"real serialized initial jump differs from neutral");
            var reference=reference();row("actual-minecraft-reference",reference);
            com.naocraftlab.skins.probe.EarsJoinBridge.use(null,true);
            var entityReference=nativeEntity(null,player,new PoseStack());row("actual-avatar-renderer-reference",entityReference);
            com.naocraftlab.skins.probe.EarsJoinBridge.use(processed,true);
            if(processed!=null){var earsOnly=nativeEntity(null,player,new PoseStack());row("same-processed-source-ears-without-cpm-binding-control",earsOnly);check(earsOnly.vertices>entityReference.vertices,"native installed Ears emits nonempty BODY without CPM binding");}
            var entityNeutral=nativeEntity(neutral,player,new PoseStack());row("actual-avatar-renderer-owned-neutral",entityNeutral);
            if(processed!=null)check(entityNeutral.vertices>measured.vertices,"installed combined native pass must retain nonempty Ears BODY in addition to exact CPM BODY");
            double normalization=Math.min(1,reference.height()/measured.height());
            var normalizedPose=new PoseStack();normalizedPose.scale((float)normalization,(float)normalization,(float)normalization);
            var normalized=nativeBody(neutral,player,normalizedPose);row("native-normalized-terminal-control",normalized);
            near(normalized.height(),reference.height(),"actual native consumer accepts height normalization");near(normalized.center(),measured.center()*normalization,"actual native consumer preserves authored center");
            check(liveState.equals(invoke(CpmNeutralGeometryProbe.class,"liveState",new Class<?>[]{ModelDefinition.class},null,live)),"neutral pass preserves exact live pose bits");
            check(player.getDefinitionFuture()==future && player.getModelDefinition0()==live,"measurement preserves managed assignment identity");
            animate(live,500);var next=nativeBody(live,player,new PoseStack());row("subsequent-live-animation",next);
            check(Math.abs(next.center()-first.center())>0.2,"same managed live animation continues");
            if(args.length>1 && Set.of("current-red","current-center-red").contains(args[1])) {
                var operands=currentOperands();
                double layout=0.97*180/2.125;
                double entityNormalization=Math.min(1,entityReference.height()/entityNeutral.height());
                double ordinaryOffset=CenteredPlayerPreviewGeometry.modernEntityTranslationY(CenteredPlayerPreviewGeometry.STANDING_PLAYER_HEIGHT)-entityReference.center();
                double expectedTranslation=CenteredPlayerPreviewGeometry.modernEntityTranslationY(CenteredPlayerPreviewGeometry.STANDING_PLAYER_HEIGHT);
                System.out.println("CURRENT_NATIVE_OPERANDS scale="+operands.scale()+" center="+operands.translation()+" expectedScale="+(layout*entityNormalization)+" expectedCenterY="+expectedTranslation);
                if(args[1].equals("current-red"))near(operands.scale(),layout*entityNormalization,"current compiled renderer must consume selected-owned native height normalization");
                else near(operands.translation().y,expectedTranslation,"current compiled renderer preserves original native placement");
            }
            if(args.length>1 && args[1].startsWith("joined-")){
                String beforeJoined=(String)invoke(CpmNeutralGeometryProbe.class,"liveState",new Class<?>[]{ModelDefinition.class},null,live);
                double layout=0.97*180/2.125;
                var preparation=CpmIntegration.preparePreview(PROFILE,APPEARANCE);
                check(preparation.status()==PreviewRenderer.BodyStatus.READY && preparation.current().getAsBoolean(),"joined selected managed source admitted");
                numeric=new NumericInput(entityNeutral.height(),entityNeutral.center(),entityReference.height(),entityReference.center(),preparation.current());
                sensitivity=args[1].substring("joined-".length());
                boolean prototype=!sensitivity.equals("current-red");
                Sink[] observed={null};
                if(prototype)CpmExtractionBodyProbe.rendererOperands=CpmCurrentNativeGeometryProbe::injectOperands;
                queuedConsumer=(bridge,state)->{
                    try{observed[0]=consumeGui(bridge,state,neutral,player);}catch(Exception e){throw new AssertionError("GUI native consumer setup/execute",e);}
                };
                try{
                    var queued=currentOperands();
                    check(observed[0]!=null,"compiled current enqueue and genuine GUI/native model consumer joined");
                    row("joined-"+sensitivity,observed[0]);
                    System.out.println("JOINED_NATIVE scale="+queued.scale()+" translationY="+queued.translation().y+" expectedHeight="+(layout*entityReference.height())+" expectedCenterY="+(layout*numeric.factor()*(CenteredPlayerPreviewGeometry.modernEntityTranslationY(CenteredPlayerPreviewGeometry.STANDING_PLAYER_HEIGHT)-entityNeutral.center())));
                    near(observed[0].height(),layout*entityReference.height(),"joined actual GUI/native consumed normalized height");
                    near(observed[0].center(),layout*numeric.factor()*(CenteredPlayerPreviewGeometry.modernEntityTranslationY(CenteredPlayerPreviewGeometry.STANDING_PLAYER_HEIGHT)-entityNeutral.center()),"joined actual GUI/native center preserves ordinary placement");
                }finally{queuedConsumer=(bridge,state)->{};CpmExtractionBodyProbe.rendererOperands=ignored->{};numeric=null;}
                check(beforeJoined.equals(invoke(CpmNeutralGeometryProbe.class,"liveState",new Class<?>[]{ModelDefinition.class},null,live)),"joined consumer preserves exact preceding live numeric pose");
                check(player.getDefinitionFuture()==future && player.getModelDefinition0()==live,"joined native consumption preserves managed assignment");
                animate(live,750);var afterJoined=nativeBody(live,player,new PoseStack());row("live-animation-after-joined-consumer",afterJoined);
                check(Math.abs(afterJoined.center()-next.center())>0.2,"same managed live animation continues after joined consumer");
            }
            } finally {neutral.cleanup();live.cleanup();CapeProjection.retireSkin(PROFILE);invoke(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);}
            java.lang.ref.Reference.reachabilityFence(texture);
            if(args.length<2 || !args[1].startsWith("joined-"))controls();
            check(networkBefore.equals(networkCounters()),"native measurement adds no auxiliary/resource/player-state/event network work");
            check(!Files.exists(out.resolve("synthetic-config.json")),"native measurement does not write configuration");
            System.out.println("NATIVE_CPM_COUNTERS "+networkCounters());
        }finally{
            if(processed!=null)processed.close();com.naocraftlab.skins.probe.EarsJoinBridge.clear();
            Files.writeString(out.resolve("native-measurements.json"),new com.google.gson.Gson().toJson(Map.of("f0Complete",false,"cases",ROWS,"networkCounters",networkCounters())));
        }
    }
}
