package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.runtime.*;
import com.naocraftlab.skins.compat.cpm.*;
import com.tom.cpm.shared.definition.*;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.client.PlayerRenderStateAccess;
import net.minecraft.client.renderer.entity.state.*;
import net.minecraft.client.renderer.state.gui.*;
import net.minecraft.client.renderer.state.gui.pip.*;
import net.minecraft.client.renderer.feature.*;
import java.util.*;
import java.util.function.*;
import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.commons.*;

public final class ReferenceBodyHost {
    static final String PREFIX="com.naocraftlab.skins.compat.client.identifier.extraction.";
    static final String PIP="net.minecraft.client.gui.render.pip.PictureInPictureRenderer", GUI="net.minecraft.client.gui.render.pip.GuiEntityRenderer";
    static PreviewRenderer.PreviewAppearance appearance;
    static Player<?> player;
    static final Map<String,com.naocraftlab.skins.probe.EarsJoinBridge.Processed> retainedInputs = new HashMap<>();
    static Loader loader;
    static Class<?> marker,contextType,stateType;
    static Object renderer;
    static CpmBakedDisplayProbe.SelectedFallback fallback;
    static PreviewRenderer.PreviewRequest lastRequest;
    public static void requested(PreviewRenderer.PreviewRequest request) { lastRequest=request; }
    static List<GuiEntityRenderState> submitted=new ArrayList<>();
    static final Map<GuiEntityRenderState,Integer> strata=new IdentityHashMap<>();
    static Runnable beforeAcquisition=()->{},afterAcquisition=()->{};
    static Object expectedMaterial;
    static int ownMaterialAcquisitions,meshVertices;
    static double meshMin,meshMax;
    public static void prepared(net.minecraft.client.renderer.rendertype.PreparedRenderType prepared){
        try{
            @SuppressWarnings("unchecked")var selected=(List<net.minecraft.client.renderer.rendertype.PreparedRenderType.Texture>)PipMaterialProbe.get(net.minecraft.client.renderer.rendertype.PreparedRenderType.class,"textures",prepared);
            if(selected.stream().anyMatch(t->t.textureView()==expectedMaterial)){ownMaterialAcquisitions++;afterAcquisition.run();}
        }catch(Exception e){throw new AssertionError(e);}
    }
    public static void mesh(com.mojang.blaze3d.vertex.MeshData data){
        if(data==null)return;var state=data.drawState();var bytes=data.vertexBuffer().order(java.nio.ByteOrder.nativeOrder());var format=state.format();int stride=format.getVertexSize(),offset=format.getElement("Position").offset();
        for(int i=0;i<state.vertexCount();i++){float y=bytes.getFloat(i*stride+offset+4);PipMaterialProbe.check(Float.isFinite(y),"real CPU buffer vertex finite");meshMin=Math.min(meshMin,y);meshMax=Math.max(meshMax,y);}meshVertices+=state.vertexCount();
    }
    static byte[] methodBytes(MethodNode method){
        var pin=new MethodNode(Opcodes.ASM9,method.access,method.name,method.desc,method.signature,method.exceptions.toArray(String[]::new));
        method.accept(pin);
        for(var instruction:pin.instructions.toArray()) {
            if(instruction instanceof MethodInsnNode call && call.name.equals("blit")
                    && call.owner.equals("net/minecraft/client/gui/GuiGraphicsExtractor"))
                call.desc=CpmExtractionBodyProbe.loadingDescriptor(call.desc);
            if(instruction instanceof MethodInsnNode constructor
                    && constructor.owner.equals(Type.getInternalName(EditorPreviewSession.Gallery.class))
                    && constructor.name.equals("<init>") && constructor.desc.equals("(Ljava/util/function/LongSupplier;)V")
                    && constructor.getPrevious() instanceof FieldInsnNode clock && clock.getOpcode()==Opcodes.GETSTATIC
                    && clock.owner.equals(Type.getInternalName(CpmExtractionBodyProbe.class))
                    && clock.name.equals("galleryClock") && clock.desc.equals("Ljava/util/function/LongSupplier;")) {
                pin.instructions.remove(clock);constructor.desc="()V";pin.maxStack--;
            }
        }
        var writer=new ClassWriter(0);writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,"MethodPin",null,"java/lang/Object",null);pin.accept(writer);writer.visitEnd();return writer.toByteArray();
    }
    static Map<String,Class<?>> types(){
        var map=CpmExtractionBodyProbe.nativeTypes();
        for(String name:new ArrayList<>(map.keySet()))if(!(name.startsWith("net/minecraft/client/player/")||name.startsWith("net/minecraft/world/")||name.equals("net/minecraft/client/Options")||name.equals("net/minecraft/client/multiplayer/ClientPacketListener")||name.equals("net/minecraft/client/multiplayer/ClientLevel")||name.equals("net/minecraft/core/ClientAsset$Texture")||name.startsWith("com/naocraftlab/skins/compat/client/identifier/CapeMaterialSnapshot")||name.endsWith("$PreviewPlayer")))map.remove(name);
        map.put("net/minecraft/client/Minecraft",RawClient.class);
        map.put("net/minecraft/client/renderer/entity/state/AvatarRenderState",RawAvatar.class);
        map.put("net/minecraft/client/gui/GuiGraphicsExtractor",Graphics.class);
        map.put("com/naocraftlab/skins/compat/client/identifier/extraction/AvatarPipPreviewRenderer",Baked.class);
        map.put(Type.getInternalName(CpmExtractionBodyProbe.Avatar.class),RawAvatar.class);
        map.put(Type.getInternalName(CpmExtractionBodyProbe.Client.class),RawClient.class);
        return map;
    }
    public static final class RawClient {
        static RawClient current;
        public CpmPreviewScopeProbe.Level level=new CpmPreviewScopeProbe.Level();
        public CpmPreviewScopeProbe.Avatar player=new CpmPreviewScopeProbe.Avatar(level);
        public CpmExtractionBodyProbe.Options options=new CpmExtractionBodyProbe.Options();
        public net.minecraft.client.renderer.GameRenderer gameRenderer=PipMaterialProbe.minecraft.gameRenderer;
        public static RawClient getInstance(){if(Thread.currentThread()!=PipCpuBackend.thread)throw new AssertionError("native client getter on worker");return current;}
        public boolean isSameThread(){return Thread.currentThread()==PipCpuBackend.thread;}
        public CpmExtractionBodyProbe.Connection getConnection(){return null;}
        public net.minecraft.client.renderer.entity.EntityRenderDispatcher getEntityRenderDispatcher(){return PipMaterialProbe.minecraft.getEntityRenderDispatcher();}
    }
    public static class RawAvatar extends AvatarRenderState implements CpmExtractionBodyProbe.MaterialCarrier {
        public CpmExtractionBodyProbe.Skin skin=new CpmExtractionBodyProbe.Skin();
        public PreviewRenderer.PreviewAppearance appearance;
        public ModelDefinition definition;
        public Player<?> ownedPlayer;
        public CpmPreviewScopeProbe.Stack headEquipment=CpmPreviewScopeProbe.Stack.EMPTY,chestEquipment=CpmPreviewScopeProbe.Stack.EMPTY,legsEquipment=CpmPreviewScopeProbe.Stack.EMPTY,feetEquipment=CpmPreviewScopeProbe.Stack.EMPTY,rightHandItemStack=CpmPreviewScopeProbe.Stack.EMPTY,leftHandItemStack=CpmPreviewScopeProbe.Stack.EMPTY;
        public void nclskins$setCapeMaterial(CpmExtractionBodyProbe.Material material){}
    }
    public static final class Baked {
        public void render(Graphics g,PreviewRenderer.PreviewRequest r){throw new AssertionError("unexpected baked fallback");}
        public void render(Graphics g,PreviewRenderer.PreviewRequest r,PreviewModelFrames.Completion display){
            Objects.requireNonNull(fallback,"unexpected baked fallback").render(r,display);
        }
        public void close(){}
    }
    public static final class Graphics {
        public final java.util.List<CpmBakedDisplayProbe.LoadingFrame> loadingFrames=new java.util.ArrayList<>();
        public void blit(Object pipeline,CpmOwnedPreviewDispatchProbe.Text texture,int x,int y,float u,float v,
                int width,int height,int sourceWidth,int sourceHeight,int textureWidth,int textureHeight,int color) {
            loadingFrames.add(new CpmBakedDisplayProbe.LoadingFrame(texture.getString(),x,y,u,v,width,height,
                    sourceWidth,sourceHeight,textureWidth,textureHeight));
        }
        public void blit(Object pipeline,Object texture,int x,int y,float u,float v,
                int width,int height,int sourceWidth,int sourceHeight,int textureWidth,int textureHeight,int color) {
            loadingFrames.add(new CpmBakedDisplayProbe.LoadingFrame(texture.toString(),x,y,u,v,width,height,
                    sourceWidth,sourceHeight,textureWidth,textureHeight));
        }
        private int stratum;
        public void nextStratum() { stratum++; }
        public void enableScissor(int left,int top,int right,int bottom) {}
        public void disableScissor() {}
        public void entity(EntityRenderState state,float scale,org.joml.Vector3fc translation,org.joml.Quaternionfc rotation,org.joml.Quaternionfc camera,int x0,int y0,int x1,int y1){
            PipMaterialProbe.check(state instanceof RawAvatar&&((RawAvatar)state).appearance==appearance,"compiled current host actual appearance captured with genuine native state");
            var pip=new GuiEntityRenderState(state,new org.joml.Vector3f(translation),new org.joml.Quaternionf(rotation),camera==null?null:new org.joml.Quaternionf(camera),x0,y0,x1,y1,scale,null);submitted.add(pip);strata.put(pip,stratum);
        }
        public void entity(EntityRenderState state,float scale,org.joml.Vector3f translation,org.joml.Quaternionf rotation,org.joml.Quaternionf camera,int x0,int y0,int x1,int y1){entity(state,scale,(org.joml.Vector3fc)translation,(org.joml.Quaternionfc)rotation,(org.joml.Quaternionfc)camera,x0,y0,x1,y1);}
    }
    public static void failure(RuntimeException failure){failure.printStackTrace(System.out);}
    static int missingRequiredChecks;
    public static boolean earsAvailability(boolean available) {
        if(!available && StackWalker.getInstance().walk(frames->frames.anyMatch(frame->frame.getClassName().equals(PREFIX+"AvatarRenderStatePreviewRenderer") && frame.getMethodName().equals("render")))) missingRequiredChecks++;
        return available;
    }
    public static void selected(com.mojang.authlib.GameProfile profile, PreviewRenderer.PreviewAppearance selected) {
        if (!retainedInputs.isEmpty()) {
            appearance=selected;
            var input=Objects.requireNonNull(retainedInputs.get(selected.skin().location()),"own retained source");
            com.naocraftlab.skins.probe.PipNativeInputs.select(input);
            expectedMaterial=input.texture().getTextureView();

        }
        com.naocraftlab.skins.runtime.CapeProjection.effectiveSkin(profile.id(),PipMaterialProbe.WORLD,
                selected.skin().location(),selected.model(),true);
        if (!retainedInputs.isEmpty()) player=ReferenceBodyProbe.definitions.loadPlayer(profile.id(),ModelDefinitionLoader.PLAYER_UNIQUE);
    }
    public static RawAvatar makeState(Object context){
        try{
            var state=(RawAvatar)stateType.getConstructor().newInstance();state.definition=player==null?null:player.getModelDefinition0();state.appearance=appearance;state.scale=1;state.pose=net.minecraft.world.entity.Pose.STANDING;
            state.ownedPlayer=player;
            state.ageInTicks=ReferenceBodyProbe.age;
            if(!ReferenceBodyProbe.neutral){state.x=2;state.z=3;state.bodyRot=28;state.walkAnimationSpeed=0.6F;}
            state.showHat=state.showJacket=state.showLeftSleeve=state.showRightSleeve=state.showLeftPants=state.showRightPants=true;
            ((AvatarRenderState)state).skin=new net.minecraft.world.entity.player.PlayerSkin(
                    new net.minecraft.core.ClientAsset.DownloadedTexture(net.minecraft.resources.Identifier.parse(appearance.skin().location()),"synthetic:owned-source"),
                    null,null,appearance.model()==SkinModel.SLIM?net.minecraft.world.entity.player.PlayerModelType.SLIM:net.minecraft.world.entity.player.PlayerModelType.WIDE,false);
            var access=(PlayerRenderStateAccess)(Object)state;access.cpm$storeState(PipMaterialProbe.avatar.getModel());access.cpm$setPlayer((Player)player);
            var animation=new com.tom.cpm.shared.animation.AnimationState(com.tom.cpm.shared.animation.AnimationEngine.AnimationMode.PLAYER);animation.syncState=new com.tom.cpm.shared.animation.ServerAnimationState();access.cpm$setAnimationState(animation);
            marker.getMethod("nclskins$setPreviewContext",contextType).invoke(state,context);return state;
        }catch(Exception e){throw new AssertionError(e);}
    }
    static final class Loader extends ClassLoader {
        final Map<String,Class<?>> mappings=types();
        boolean withoutEars;
        Loader(){super(ReferenceBodyHost.class.getClassLoader());}
        @Override public java.net.URL getResource(String name) {
            return withoutEars && name.equals("diy/y2k/five/thermite/FeaturesHolder.class") ? null : super.getResource(name);
        }
        @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
            if(!name.startsWith(PREFIX)&&!name.startsWith("com.naocraftlab.skins.compat.client.identifier.preview.")&&!name.equals("com.naocraftlab.skins.compat.client.identifier.measurement.AvatarEarsFitMode")&&!name.equals(PIP)&&!name.equals(GUI)&&!name.equals("com.naocraftlab.skins.compat.client.identifier.IdentifierOuterLayerVisibilityController"))return super.loadClass(name,resolve);
            var type=findLoadedClass(name);
            if(type==null)try(var in=getParent().getResourceAsStream(name.replace('.','/')+".class")){
                var original=in.readAllBytes();var node=new ClassNode();new ClassReader(original).accept(node,0);var originalPath=Path.of(System.getProperty("probe.dir"),"current-host-original",name.replace('.','/')+".class");Files.createDirectories(originalPath.getParent());Files.write(originalPath,original);
                if(name.equals(PREFIX+"AvatarRenderStatePreviewRenderer")){
                    var configure=node.methods.stream().filter(m->m.name.equals("configurePreviewState")).findFirst().orElseThrow();
                    var fallbackMethod=node.methods.stream().filter(m->m.name.equals("renderFallback")).findFirst().orElseThrow();
                    var preserved=new HashMap<String,byte[]>();for(var m:node.methods)if(Set.of("modelFitApplicable","prepareModelFit","suppressModelFit","configurePreviewState","render","prepareBody","retireBody","prepareGalleryBody","renderGallery","transformRetained","confirmGalleryBody","retireGallerySlots","selectGallerySlot","saveGallerySlot","body","observeBody","close").contains(m.name)||m.name.startsWith("lambda$"))preserved.put(m.name+m.desc,methodBytes(m));
                    preserved.put(fallbackMethod.name+fallbackMethod.desc,methodBytes(fallbackMethod));
                    PipMaterialProbe.call(CpmExtractionBodyProbe.class,"rendererTerminals",new Class<?>[]{ClassNode.class},null,node);node.methods.removeIf(m->m.name.equals("configurePreviewState"));node.methods.add(configure);node.methods.add(fallbackMethod);
                    for(var m:node.methods)if(preserved.containsKey(m.name+m.desc)){PipMaterialProbe.check(Arrays.equals(preserved.remove(m.name+m.desc),methodBytes(m)),"current host orchestration/currentness/lifetime algorithm unchanged "+m.name);}PipMaterialProbe.check(preserved.isEmpty(),"all retained current host algorithm methods preserved");
                    for(var m:node.methods)if(m.name.equals("render") && m.desc.contains("PreviewRequest")) {
                        var ins=new InsnList();ins.add(new VarInsnNode(Opcodes.ALOAD,2));
                        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC,Type.getInternalName(ReferenceBodyHost.class),"requested","(Lcom/naocraftlab/skins/client/PreviewRenderer$PreviewRequest;)V",false));m.instructions.insert(ins);
                    }
                    for(var m:node.methods)if(m.name.equals("previewPlayer")) {
                        var ins=new InsnList();ins.add(new VarInsnNode(Opcodes.ALOAD,0));
                        ins.add(new FieldInsnNode(Opcodes.GETFIELD,node.name,"previewProfile","Lcom/mojang/authlib/GameProfile;"));
                        ins.add(new VarInsnNode(Opcodes.ALOAD,2));
                        ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC,Type.getInternalName(ReferenceBodyHost.class),"selected",
                                "(Lcom/mojang/authlib/GameProfile;Lcom/naocraftlab/skins/client/PreviewRenderer$PreviewAppearance;)V",false));m.instructions.insert(ins);
                    }
                    for(var m:node.methods)if(m.name.equals("onLiveRenderFailure")){var ins=new InsnList();ins.add(new VarInsnNode(Opcodes.ALOAD,3));ins.add(new MethodInsnNode(Opcodes.INVOKESTATIC,Type.getInternalName(ReferenceBodyHost.class),"failure","(Ljava/lang/RuntimeException;)V",false));m.instructions.insert(ins);}
                    for(var m:node.methods)for(var ins:m.instructions.toArray())if(ins instanceof MethodInsnNode call&&call.owner.equals(Type.getInternalName(CpmExtractionBodyProbe.class))&&call.name.equals("makeState")){call.owner=Type.getInternalName(ReferenceBodyHost.class);call.desc="(Ljava/lang/Object;)"+Type.getDescriptor(RawAvatar.class);}
                }
                if(name.equals("com.naocraftlab.skins.compat.client.identifier.measurement.AvatarEarsFitMode")) {
                    var available=node.methods.stream().filter(method->method.name.equals("earsAvailable") && method.desc.equals("()Z")).findFirst().orElseThrow();
                    for(var instruction:available.instructions.toArray()) if(instruction.getOpcode()==Opcodes.IRETURN)
                        available.instructions.insertBefore(instruction,new MethodInsnNode(Opcodes.INVOKESTATIC,Type.getInternalName(ReferenceBodyHost.class),"earsAvailability","(Z)Z",false));
                }
                var writer=new ClassWriter(0);node.accept(new ClassRemapper(writer,new Remapper(Opcodes.ASM9){@Override public String map(String n){var c=mappings.get(n);return c==null?n:Type.getInternalName(c);}}));var bytes=writer.toByteArray();
                var output=Path.of(System.getProperty("probe.dir"),"current-host",name.replace('.','/')+".class");Files.createDirectories(output.getParent());Files.write(output,bytes);type=defineClass(name,bytes,0,bytes.length);
            }catch(Exception e){throw new ClassNotFoundException(name,e);}
            if(resolve)resolveClass(type);return type;
        }
        Class<?> state(Class<?> marker)throws Exception{
            String name=PREFIX+"PipRawInputState";var w=new ClassWriter(0);w.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,name.replace('.','/'),null,Type.getInternalName(RawAvatar.class),new String[]{Type.getInternalName(marker)});
            var init=w.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);init.visitVarInsn(Opcodes.ALOAD,0);init.visitMethodInsn(Opcodes.INVOKESPECIAL,Type.getInternalName(RawAvatar.class),"<init>","()V",false);init.visitInsn(Opcodes.RETURN);init.visitMaxs(1,1);init.visitEnd();
            for(String field:new String[]{"DepthExtent","FailureSink","LayerFailureSink","PreviewContext"}){
                var getter=marker.getMethod("nclskins$"+Character.toLowerCase(field.charAt(0))+field.substring(1));String desc=Type.getDescriptor(getter.getReturnType());w.visitField(Opcodes.ACC_PUBLIC,field,desc,null,null).visitEnd();
                var g=w.visitMethod(Opcodes.ACC_PUBLIC,getter.getName(),"()"+desc,null,null);g.visitVarInsn(Opcodes.ALOAD,0);g.visitFieldInsn(Opcodes.GETFIELD,name.replace('.','/'),field,desc);g.visitInsn(desc.equals("F")?Opcodes.FRETURN:Opcodes.ARETURN);g.visitMaxs(1,1);g.visitEnd();
                var s=w.visitMethod(Opcodes.ACC_PUBLIC,"nclskins$set"+field,"("+desc+")V",null,null);s.visitVarInsn(Opcodes.ALOAD,0);s.visitVarInsn(desc.equals("F")?Opcodes.FLOAD:Opcodes.ALOAD,1);s.visitFieldInsn(Opcodes.PUTFIELD,name.replace('.','/'),field,desc);s.visitInsn(Opcodes.RETURN);s.visitMaxs(2,2);s.visitEnd();
            }
            w.visitEnd();var bytes=w.toByteArray();return defineClass(name,bytes,0,bytes.length);
        }
    }
    static void initialize()throws Exception{
        loader=new Loader();marker=loader.loadClass(PREFIX+"NclSkinsWideDepthState");contextType=loader.loadClass(PREFIX+"AvatarPreviewContext");stateType=loader.state(marker);RawClient.current=new RawClient();
        var type=loader.loadClass(PREFIX+"AvatarRenderStatePreviewRenderer");renderer=PipMaterialProbe.allocate(type);
        PipMaterialProbe.set(type,"session",renderer,new EditorPreviewSession());PipMaterialProbe.set(type,"gallerySlots",renderer,new LinkedHashMap<>());
        PipMaterialProbe.set(type,"previewProfile",renderer,new com.mojang.authlib.GameProfile(PipMaterialProbe.PROFILE,"SyntheticNative"));PipMaterialProbe.set(type,"previewClock",renderer,new EditorPreviewClock());PipMaterialProbe.set(type,"previewTickGate",renderer,new EditorPreviewTickGate());PipMaterialProbe.set(type,"bakedRenderer",renderer,new Baked());
        PipMaterialProbe.set(type,"diagnostics",renderer,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());PipMaterialProbe.set(type,"accessoryObserver",renderer,(Consumer<PreviewAccessories>)ignored->{});PipMaterialProbe.set(type,"bodyObserver",renderer,(Consumer<PreviewRenderer.BodyStatus>)ignored->{});
    }

}
