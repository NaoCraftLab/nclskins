package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.runtime.*;
import com.mojang.blaze3d.vertex.*;
import com.mojang.blaze3d.systems.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.state.*;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.client.model.geom.*;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.gui.render.state.*;
import net.minecraft.client.gui.render.state.pip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.commons.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

public final class SubmissionCaptureBackend implements AutoCloseable {
    static final String PREFIX="com.naocraftlab.skins.compat.client.identifier.submission.";
    static SubmissionCaptureBackend active;
    final Object oldClient;
    final Map<String,Object> previous=new LinkedHashMap<>();
    Minecraft minecraft;
    TextureManager textures;
    ByteBufferBuilder bytes;
    MultiBufferSource.BufferSource buffers;
    FeatureRenderDispatcher features;
    com.mojang.blaze3d.pipeline.TextureTarget target;
    Avatar avatar;
    final RawClient client=new RawClient();
    final List<PictureInPictureRenderState> submitted=new ArrayList<>();
    final java.util.concurrent.BlockingQueue<Runnable> nativeQueue=new java.util.concurrent.LinkedBlockingQueue<>();
    PreviewRenderer.PreviewAppearance appearance;
    com.mojang.authlib.GameProfile profile;
    Object owner;
    long roots;
    long materialBindings;
    com.tom.cpm.client.PlayerRenderManager manager;
    com.tom.cpm.shared.MinecraftClientAccess oldAccess;
    Object oldNativeClient;
    Object nativeClient;
    Object oldManager;
    Object oldClientBase;
    boolean upstreamInitialized;

    private boolean closed;

    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    public static void nativeFailure(RuntimeException failure){System.out.println("SUBMISSION_NATIVE_FAILURE type="+failure.getClass().getName()+" at="+failure.getStackTrace()[0].getClassName()+"."+failure.getStackTrace()[0].getMethodName());}
    static Object get(Class<?> type,String name,Object instance)throws Exception{var field=type.getDeclaredField(name);field.setAccessible(true);return field.get(instance);}
    static void set(Class<?> type,String name,Object instance,Object value)throws Exception{var field=type.getDeclaredField(name);field.setAccessible(true);field.set(instance,value);}
    static Object allocate(Class<?> type)throws Exception{var field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);return ((sun.misc.Unsafe)field.get(null)).allocateInstance(type);}

    SubmissionCaptureBackend()throws Exception{
        check(active==null,"single submission CPU owner");
        org.spongepowered.asm.launch.MixinBootstrap.init();
        var transformer = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();
        transformer.setAccessible(true);transformer.newInstance();
        com.llamalad7.mixinextras.MixinExtrasBootstrap.init();
        com.llamalad7.mixinextras.service.MixinExtrasService.getFrom(
                com.llamalad7.mixinextras.utils.Blackboard.get("MixinExtrasServiceInstance")).initialize();
        com.llamalad7.mixinextras.sugar.impl.ref.LocalRefClassGenerator.getForType(Type.getType(Object.class));
        for(String name:List.of("net/minecraft/client/renderer/entity/LivingEntityRenderer","net/minecraft/client/renderer/MultiBufferSource$BufferSource")){
            var node=new ClassNode();try(var input=SubmissionCaptureBackend.class.getClassLoader().getResourceAsStream(name+".class")){check(input!=null,"current native producer definition is present");new ClassReader(input.readAllBytes()).accept(node,0);}
            String hook=name.endsWith("LivingEntityRenderer")?"root":"vertex";boolean found=false;
            for(var method:node.methods)for(var instruction:method.instructions.toArray())if(instruction instanceof MethodInsnNode call&&call.owner.equals("com/naocraftlab/skins/compat/client/identifier/measurement/AvatarBodyCapture")&&call.name.equals(hook)&&call.getOpcode()==Opcodes.INVOKESTATIC&&call.desc.equals(hook.equals("root")?"(Ljava/lang/Object;Lorg/joml/Matrix4f;)V":"(FFF)V"))found=true;
            check(found,"current production capture hook selected: "+hook);
        }

        oldClient=get(Minecraft.class,"instance",null);
        for(String name:List.of("DEVICE","renderThread","dynamicUniforms","projectionMatrixBuffer","projectionType","outputColorTextureOverride","outputDepthTextureOverride"))previous.put(name,get(RenderSystem.class,name,null));
        try {
        var device=SubmissionCaptureDevice.device();
        set(RenderSystem.class,"DEVICE",null,device);set(RenderSystem.class,"renderThread",null,Thread.currentThread());set(RenderSystem.class,"dynamicUniforms",null,new DynamicUniforms());
        minecraft=(Minecraft)allocate(Minecraft.class);set(Minecraft.class,"instance",null,minecraft);
        var level=(LevelRenderer)allocate(LevelRenderer.class);set(LevelRenderer.class,"targets",level,allocate(LevelTargetBundle.class));set(Minecraft.class,"levelRenderer",minecraft,level);
        var game=(GameRenderer)allocate(GameRenderer.class);set(Minecraft.class,"gameRenderer",minecraft,game);set(GameRenderer.class,"overlayTexture",game,new OverlayTexture());
        var light=(LightTexture)allocate(LightTexture.class);var texture=device.createTexture("submission readonly light",com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING,com.mojang.blaze3d.textures.TextureFormat.RGBA8,16,16,1,1);set(LightTexture.class,"texture",light,texture);set(LightTexture.class,"textureView",light,device.createTextureView(texture));set(GameRenderer.class,"lightTexture",game,light);
        textures=(TextureManager)allocate(TextureManager.class);set(TextureManager.class,"byPath",textures,new HashMap<net.minecraft.resources.Identifier,AbstractTexture>());set(TextureManager.class,"tickableTextures",textures,new HashSet<>());set(Minecraft.class,"textureManager",minecraft,textures);
        target=new com.mojang.blaze3d.pipeline.TextureTarget("submission capture output",160,180,true);set(Minecraft.class,"mainRenderTarget",minecraft,target);
        bytes=new ByteBufferBuilder(65536);buffers=MultiBufferSource.immediate(bytes);features=new FeatureRenderDispatcher(new SubmitNodeStorage(),null,buffers,null,null,buffers,null);
        oldAccess=com.tom.cpm.shared.MinecraftClientAccess.get();
        oldClientBase=get(com.tom.cpm.client.ClientBase.class,"mc",null);
        upstreamInitialized=true;
        var nativeObject=new com.tom.cpm.client.MinecraftObject();manager=nativeObject.getPlayerRenderManager();set(com.tom.cpm.client.ClientBase.class,"mc",null,nativeObject);
        var clientType=Class.forName("com.tom.cpm.client.CustomPlayerModelsClient");oldNativeClient=get(clientType,"INSTANCE",null);nativeClient=oldNativeClient;
        if(nativeClient==null){nativeClient=clientType.getConstructor().newInstance();set(clientType,"INSTANCE",null,nativeClient);}
        oldManager=get(com.tom.cpm.client.ClientBase.class,"manager",nativeClient);set(com.tom.cpm.client.ClientBase.class,"manager",nativeClient,new com.tom.cpm.shared.model.RenderManager(manager,null,java.util.function.Function.identity()));
        if(getClass().getClassLoader().getResource("net/neoforged/neoforge/client/event/RenderPlayerEvent.class")!=null)
            neoEvents=(AutoCloseable)Class.forName("com.naocraftlab.skins.buildlogic.probe.NeoImmediateEvents").getConstructor(Object.class).newInstance(nativeClient);
        var previousAccess=oldAccess;
        com.tom.cpm.shared.MinecraftObjectHolder.setClientObject((com.tom.cpm.shared.MinecraftClientAccess)java.lang.reflect.Proxy.newProxyInstance(SubmissionCaptureBackend.class.getClassLoader(),new Class<?>[]{com.tom.cpm.shared.MinecraftClientAccess.class},(proxy,method,args)->method.getName().equals("createTexture")?nativeObject.createTexture():method.invoke(previousAccess,args)));
        for(String resource:List.of("white.png","template/cape.png"))try(var input=getClass().getClassLoader().getResourceAsStream("assets/cpm/textures/"+resource)){check(input!=null,"pinned public CPM material");textures.register(net.minecraft.resources.Identifier.parse("cpm:textures/"+resource),new DynamicTexture(()->"public CPM material",com.mojang.blaze3d.platform.NativeImage.read(input)));}
        var context=new EntityRendererProvider.Context(null,null,null,null,null,models(),new net.minecraft.client.resources.model.EquipmentAssetManager(),null,null,null);
        avatar=new Avatar(context);active=this;
        } catch (Exception | Error failure) {
            try { close(); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    static EntityModelSet models(){
        var roots=com.google.common.collect.ImmutableMap.<ModelLayerLocation,LayerDefinition>builder();
        ModelLayers.PLAYER_ARMOR.putFrom(PlayerModel.createArmorMeshSet(new CubeDeformation(0.5F),new CubeDeformation(1)).map(mesh->LayerDefinition.create(mesh,64,32)),roots);
        roots.put(ModelLayers.PLAYER,LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE,false),64,64));
        roots.put(ModelLayers.ARROW,net.minecraft.client.model.object.projectile.ArrowModel.createBodyLayer());roots.put(ModelLayers.BEE_STINGER,net.minecraft.client.model.animal.bee.BeeStingerModel.createBodyLayer());roots.put(ModelLayers.PLAYER_EARS,net.minecraft.client.model.player.PlayerEarsModel.createEarsLayer());roots.put(ModelLayers.PLAYER_CAPE,net.minecraft.client.model.player.PlayerCapeModel.createCapeLayer());
        var wings=net.minecraft.client.model.object.equipment.ElytraModel.createLayer();roots.put(ModelLayers.ELYTRA,wings);roots.put(ModelLayers.ELYTRA_BABY,wings.apply(net.minecraft.client.model.object.equipment.ElytraModel.BABY_TRANSFORMER));roots.put(ModelLayers.PARROT,net.minecraft.client.model.animal.parrot.ParrotModel.createBodyLayer());roots.put(ModelLayers.PLAYER_SPIN_ATTACK,net.minecraft.client.model.effects.SpinAttackEffectModel.createLayer());return new EntityModelSet(roots.buildOrThrow());
    }

    static final class Avatar extends net.minecraft.client.renderer.entity.player.AvatarRenderer<net.minecraft.client.player.RemotePlayer>{
        Avatar(EntityRendererProvider.Context context){super(context,false);}
        @Override protected net.minecraft.client.renderer.rendertype.RenderType getRenderType(AvatarRenderState state,boolean visible,boolean translucent,boolean outline){
            if(active!=null&&active.profile!=null){var source=CapeProjection.skinSource(active.profile.id());boolean own=active.manager.getHolderSafe(getModel(),null,holder->holder.swappedIn&&holder.def instanceof com.naocraftlab.skins.compat.cpm.OwnedCpmDefinition owned&&owned.source()==source&&source.active(),false,false);if(own)active.materialBindings++;}
            return super.getRenderType(state,visible,translucent,outline);
        }
        @Override protected void submitNameTag(AvatarRenderState state,PoseStack poses,SubmitNodeCollector collector,CameraRenderState camera){}
    }
    public static final class RawGame{
        public CpmPreviewScopeProbe.Light getLighting(){return new CpmPreviewScopeProbe.Light();}
        public FeatureRenderDispatcher getFeatureRenderDispatcher(){return active.features;}
    }
    public static final class RawClient{
        public final CpmPreviewScopeProbe.Level level=new CpmPreviewScopeProbe.Level();
        public final CpmPreviewScopeProbe.Avatar player=new CpmPreviewScopeProbe.Avatar(level);
        public final CpmExtractionBodyProbe.Options options=new CpmExtractionBodyProbe.Options();
        public final RawGame gameRenderer=new RawGame();
        public static RawClient getInstance(){return active.client;}
        public CpmPreviewScopeProbe.Delta getDeltaTracker(){return ignored->0;}
        public Dispatcher getEntityRenderDispatcher(){return new Dispatcher();}
        public boolean isSameThread(){return Thread.currentThread()==SubmissionCaptureDevice.thread;}
        public CpmExtractionBodyProbe.Connection getConnection(){return null;}
    }
    public static final class SourceHost implements java.util.concurrent.Executor{
        public static SourceHost getInstance(){return new SourceHost();}
        public java.net.Proxy getProxy(){return java.net.Proxy.NO_PROXY;}
        public boolean isSameThread(){return Thread.currentThread()==SubmissionCaptureDevice.thread;}
        public TextureManager getTextureManager(){return active.textures;}
        public void execute(Runnable action){active.nativeQueue.add(action);}
        public <V>java.util.concurrent.CompletableFuture<V> submit(Supplier<V> action){var future=new java.util.concurrent.CompletableFuture<V>();execute(()->{try{future.complete(action.get());}catch(RuntimeException|Error failure){future.completeExceptionally(failure);}});return future;}
    }
    void drain(){while(!nativeQueue.isEmpty())nativeQueue.remove().run();CpmBehaviorProbe.drain();}
    public static class RawAvatar extends AvatarRenderState implements CpmExtractionBodyProbe.MaterialCarrier{
        public CpmExtractionBodyProbe.Skin skin;
        public CpmPreviewScopeProbe.Stack headEquipment=CpmPreviewScopeProbe.Stack.EMPTY,chestEquipment=CpmPreviewScopeProbe.Stack.EMPTY,legsEquipment=CpmPreviewScopeProbe.Stack.EMPTY,feetEquipment=CpmPreviewScopeProbe.Stack.EMPTY,rightHandItemStack=CpmPreviewScopeProbe.Stack.EMPTY,leftHandItemStack=CpmPreviewScopeProbe.Stack.EMPTY;
        public CpmExtractionBodyProbe.Material material;
        public void nclskins$setCapeMaterial(CpmExtractionBodyProbe.Material material){this.material=material;}
    }
    public static RawAvatar state(){
        var state=new RawAvatar();state.ageScale=1;state.useItemHand=net.minecraft.world.InteractionHand.MAIN_HAND;state.scale=1;state.pose=net.minecraft.world.entity.Pose.STANDING;state.mainArm=state.attackArm=net.minecraft.world.entity.HumanoidArm.RIGHT;state.rightArmPose=state.leftArmPose=net.minecraft.client.model.HumanoidModel.ArmPose.EMPTY;state.speedValue=1;
        if(active.profile!=null){var player=com.tom.cpm.shared.MinecraftClientAccess.get().getDefinitionLoader().loadPlayer(active.profile,com.tom.cpm.shared.definition.ModelDefinitionLoader.PLAYER_UNIQUE);var access=(com.tom.cpm.client.PlayerRenderStateAccess)(Object)state;active.avatar.getModel().resetPose();access.cpm$storeState(active.avatar.getModel());access.cpm$setPlayer((com.tom.cpm.shared.config.Player)player);var animation=new com.tom.cpm.shared.animation.AnimationState(com.tom.cpm.shared.animation.AnimationEngine.AnimationMode.PLAYER);animation.syncState=new com.tom.cpm.shared.animation.ServerAnimationState();access.cpm$setAnimationState(animation);}
        return state;
    }
    public static void configureNativeSkin(RawAvatar state,PreviewRenderer.PreviewRequest request){
        active.appearance=request.appearance();state.skin=new CpmExtractionBodyProbe.Skin();
        ((AvatarRenderState)state).skin=new net.minecraft.world.entity.player.PlayerSkin(new net.minecraft.core.ClientAsset.DownloadedTexture(net.minecraft.resources.Identifier.parse(request.appearance().skin().location()),"synthetic:own-source"),null,null,request.appearance().model()==SkinModel.SLIM?net.minecraft.world.entity.player.PlayerModelType.SLIM:net.minecraft.world.entity.player.PlayerModelType.WIDE,false);
    }
    public static final class Dispatcher{
        public EntityRenderState extractEntity(CpmPreviewScopeProbe.Entity player,float partial){return state();}
        public void submit(EntityRenderState state,CameraRenderState camera,double x,double y,double z,PoseStack poses,SubmitNodeCollector collector){check(state instanceof RawAvatar,"same current native capture state");active.avatar.submit((AvatarRenderState)state,poses,collector,camera);active.roots++;}
    }
    public static final class Graphics extends CpmOwnedPreviewDispatchProbe.Graphics{
        public void blit(com.mojang.blaze3d.pipeline.RenderPipeline pipeline,net.minecraft.resources.Identifier texture,int x,int y,float u,float v,int width,int height,int sourceWidth,int sourceHeight,int textureWidth,int textureHeight,int color){capeCrops.add(new int[]{x,y,width,height,(int)u,(int)v,sourceWidth,sourceHeight,textureWidth,textureHeight});}
        public void submitEntityRenderState(EntityRenderState state,float scale,org.joml.Vector3f translation,org.joml.Quaternionf rotation,org.joml.Quaternionf camera,int x0,int y0,int x1,int y1){
            try{var value=new GuiEntityRenderState(state,translation,rotation,camera,x0,y0,x1,y1,scale,null);var replace=active.owner.getClass().getClassLoader().loadClass(PREFIX+"LivePreviewSubmission").getMethod("replace",Object.class,PictureInPictureRenderState.class);active.submitted.add((PictureInPictureRenderState)replace.invoke(null,this,value));}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        }
    }
    public static final class Baked{
        public void render(Graphics graphics,PreviewRenderer.PreviewRequest request,PreviewModelFrames.Completion completion){try{var session=(EditorPreviewSession)get(active.owner.getClass(),"session",active.owner);throw new AssertionError("submission fallback reached: nativeReady="+NativePlayerSkinLifecycle.isReady(request.appearance().skin().location())+" hidden="+(request.left()<0)+" intent="+request.intent()+" disabled="+get(EditorPreviewSession.class,"liveDisabled",session)+" body="+get(EditorPreviewSession.class,"bodyStatus",session));}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}catch(Exception failure){throw new AssertionError(failure);}}
        public void render(Graphics graphics,PreviewRenderer.PreviewRequest request,Consumer<PreviewModelFrames.Body> completion){throw new AssertionError("unexpected submission fallback");}
        public void close(){}
    }
    public static CpmPreviewScopeProbe.Avatar player(Object owner,RawClient client,PreviewRenderer.PreviewAppearance appearance){
        try{active.profile=(com.mojang.authlib.GameProfile)get(owner.getClass(),"profile",owner);CapeProjection.effectiveSkin(active.profile.id(),new UUID(0,1),appearance.skin().location(),appearance.model(),true);var player=(CpmPreviewScopeProbe.Avatar)get(owner.getClass(),"previewPlayer",owner);if(player==null){player=new CpmPreviewScopeProbe.Avatar(client.level);set(owner.getClass(),"previewPlayer",owner,player);set(owner.getClass(),"previewLevel",owner,client.level);}return player;}catch(Exception failure){throw new AssertionError(failure);}
    }


    static final class Registry implements TextureRegistry{
        final com.naocraftlab.skins.compat.client.identifier.IdentifierTextureRegistry nativeRegistry=new com.naocraftlab.skins.compat.client.identifier.IdentifierTextureRegistry();
        final Map<TextureHandle,byte[]> registered=new HashMap<>();
        public TextureHandle register(TextureKind kind,String digest,Path file)throws java.io.IOException{return register(kind,digest,Files.readAllBytes(file));}
        public TextureHandle register(TextureKind kind,String digest,byte[] content)throws java.io.IOException{var handle=nativeRegistry.register(kind,digest,content);registered.put(handle,content.clone());return handle;}
        public TextureUseValidity capture(TextureHandle handle){return nativeRegistry.capture(handle);}
        public void release(TextureHandle handle){nativeRegistry.release(handle);}
        public void close(){nativeRegistry.close();}
    }
    static final class Loader extends ClassLoader{
        final Map<String,Class<?>> names=new HashMap<>();
        Loader()throws Exception{
            super(SubmissionCaptureBackend.class.getClassLoader());
            var templateType=Class.forName(CpmBakedDisplayProbe.class.getName()+"$Loader");var constructor=templateType.getDeclaredConstructor(String.class,String.class,String.class,String.class,boolean.class);constructor.setAccessible(true);var template=constructor.newInstance(PREFIX,PREFIX+"SubmissionPreviewRenderer",PREFIX+"SimplePreviewRenderer",PREFIX+"BakedPreviewRenderer",false);
            @SuppressWarnings("unchecked")var mappings=(Map<String,Class<?>>)get(templateType,"types",template);
            for(var entry:mappings.entrySet())if(entry.getKey().startsWith("net/minecraft/world/")||entry.getKey().startsWith("net/minecraft/client/player/")||entry.getKey().startsWith("net/minecraft/client/multiplayer/")||entry.getKey().equals("net/minecraft/client/entity/ClientAvatarState")||entry.getKey().equals("net/minecraft/client/DeltaTracker")||entry.getKey().equals("net/minecraft/client/Options")||entry.getKey().endsWith("$PreviewPlayer")||entry.getKey().startsWith("com/naocraftlab/skins/compat/client/identifier/CapeMaterialSnapshot")||entry.getKey().startsWith("net/minecraft/core/ClientAsset$"))names.put(entry.getKey(),entry.getValue());
            names.put("net/minecraft/world/level/ItemLike",CpmPreviewScopeProbe.ItemLike.class);names.put("net/minecraft/world/item/Items",CpmPreviewScopeProbe.Items.class);names.put("net/minecraft/world/item/Item",CpmPreviewScopeProbe.Item.class);names.put("net/minecraft/client/Minecraft",RawClient.class);names.put("net/minecraft/client/gui/GuiGraphics",Graphics.class);names.put("net/minecraft/client/renderer/GameRenderer",RawGame.class);names.put("net/minecraft/client/renderer/entity/EntityRenderDispatcher",Dispatcher.class);names.put("net/minecraft/client/renderer/entity/state/AvatarRenderState",RawAvatar.class);names.put("com/mojang/blaze3d/platform/Lighting",CpmPreviewScopeProbe.Light.class);names.put("com/mojang/blaze3d/platform/Lighting$Entry",CpmPreviewScopeProbe.Light.Entry.class);names.put((PREFIX+"SimplePreviewRenderer").replace('.','/'),Baked.class);
        }
        @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
            if(!name.startsWith(PREFIX))return super.loadClass(name,resolve);
            var type=findLoadedClass(name);
            if(type==null)try(var input=getParent().getResourceAsStream(name.replace('.','/')+".class")){
                if(input==null)throw new ClassNotFoundException(name);var node=new ClassNode();new ClassReader(input.readAllBytes()).accept(node,0);
                if(name.equals(PREFIX+"SubmissionPreviewRenderer"))node.methods.removeIf(method->method.name.equals("texture"));
                if(name.equals(PREFIX+"SubmissionPreviewRenderer"))for(var method:node.methods)if(method.name.equals("onLiveRenderFailure")) {
                    check(method.desc.equals("(Lcom/naocraftlab/skins/client/EditorPreviewSession;Ljava/util/function/Consumer;Ljava/lang/RuntimeException;)V"),"exact native failure callback ABI");
                    var observe=new InsnList();observe.add(new VarInsnNode(Opcodes.ALOAD,3));observe.add(new MethodInsnNode(Opcodes.INVOKESTATIC,Type.getInternalName(SubmissionCaptureBackend.class),"nativeFailure","(Ljava/lang/RuntimeException;)V",false));method.instructions.insert(observe);
                }
                if(name.equals(PREFIX+"SubmissionPreviewRenderer"))for(var method:node.methods){
                    if(!Set.of("playerSkin","previewPlayer","applySettling").contains(method.name))continue;
                    method.instructions.clear();method.tryCatchBlocks.clear();method.localVariables=null;
                    switch(method.name){
                        case "playerSkin"->{var skin=Type.getInternalName(CpmExtractionBodyProbe.Skin.class);method.visitTypeInsn(Opcodes.NEW,skin);method.visitInsn(Opcodes.DUP);method.visitMethodInsn(Opcodes.INVOKESPECIAL,skin,"<init>","()V",false);method.visitInsn(Opcodes.ARETURN);}
                        case "previewPlayer"->{method.visitVarInsn(Opcodes.ALOAD,0);method.visitVarInsn(Opcodes.ALOAD,1);method.visitVarInsn(Opcodes.ALOAD,2);method.visitMethodInsn(Opcodes.INVOKESTATIC,Type.getInternalName(SubmissionCaptureBackend.class),"player","(Ljava/lang/Object;"+Type.getDescriptor(RawClient.class)+Type.getDescriptor(PreviewRenderer.PreviewAppearance.class)+")"+Type.getDescriptor(CpmPreviewScopeProbe.Avatar.class),false);method.visitInsn(Opcodes.ARETURN);}
                        default->method.visitInsn(Opcodes.RETURN);
                    }
                }
                if(name.equals(PREFIX+"SubmissionPreviewRenderer"))for(var method:node.methods)if(method.name.equals("configure"))for(var instruction:method.instructions.toArray())if(instruction.getOpcode()==Opcodes.RETURN){
                    var bridge=new InsnList();bridge.add(new VarInsnNode(Opcodes.ALOAD,0));bridge.add(new VarInsnNode(Opcodes.ALOAD,1));bridge.add(new MethodInsnNode(Opcodes.INVOKESTATIC,Type.getInternalName(SubmissionCaptureBackend.class),"configureNativeSkin","("+Type.getDescriptor(RawAvatar.class)+Type.getDescriptor(PreviewRenderer.PreviewRequest.class)+")V",false));method.instructions.insertBefore(instruction,bridge);
                }
                for(var method:node.methods)for(var instruction:method.instructions.toArray())if(instruction instanceof TypeInsnNode allocation&&allocation.getOpcode()==Opcodes.NEW&&allocation.desc.equals("net/minecraft/client/renderer/entity/state/AvatarRenderState")){
                    var duplicate=allocation.getNext();var initialize=duplicate.getNext();check(duplicate.getOpcode()==Opcodes.DUP&&initialize instanceof MethodInsnNode call&&call.name.equals("<init>")&&call.desc.equals("()V"),"exact native state constructor");method.instructions.set(allocation,new MethodInsnNode(Opcodes.INVOKESTATIC,Type.getInternalName(SubmissionCaptureBackend.class),"state","()"+Type.getDescriptor(RawAvatar.class),false));method.instructions.remove(duplicate);method.instructions.remove(initialize);
                }
                var writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(new ClassRemapper(writer,new Remapper(Opcodes.ASM9){@Override public String map(String value){var mapped=names.get(value);return mapped==null?value:Type.getInternalName(mapped);}}));var bytes=writer.toByteArray();type=defineClass(name,bytes,0,bytes.length);
            }catch(java.io.IOException failure){throw new ClassNotFoundException(name,failure);}
            if(resolve)resolveClass(type);return type;
        }
    }
    static void initializeOwner(Class<?> type,Object owner)throws Exception{
        for(var field:type.getDeclaredFields()){
            Object value=switch(field.getName()){
                case "session"->new EditorPreviewSession();case "gallerySlots"->new LinkedHashMap<>();case "profile"->new com.mojang.authlib.GameProfile(UUID.randomUUID(),"SyntheticNative");case "previewClock"->new EditorPreviewClock();case "previewTickGate"->new EditorPreviewTickGate();case "liveTarget"->new ScreenOwnedRenderTarget();case "baked"->new Baked();case "diagnostics"->com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding();case "bodyObserver"->(Consumer<PreviewRenderer.BodyStatus>)ignored->{};case "accessoryObserver"->(Consumer<PreviewAccessories>)ignored->{};default->null;
            };if(value!=null){field.setAccessible(true);field.set(owner,value);}
        }
    }
    static void invoke(java.lang.reflect.Method method,Object owner,Object... arguments)throws Exception{
        try{method.setAccessible(true);method.invoke(owner,arguments);}catch(java.lang.reflect.InvocationTargetException failure){var cause=failure.getCause();if(cause instanceof Exception exception)throw exception;if(cause instanceof Error error)throw error;throw failure;}
    }
    static void sync(java.lang.reflect.Method method,Object host,GalleryNativePassFixture subject)throws Exception{invoke(method,host,subject.runtime.view(854,480,0,0));}
    static void draw(java.lang.reflect.Method method,Object host,GalleryNativePassFixture subject)throws Exception{active.submitted.clear();invoke(method,host,new Graphics(),subject.runtime.view(854,480,0,0));}
    static long capturedVertices(Object owner)throws Exception{
        var fitting=get(owner.getClass(),"nativeFit",owner);if(fitting==null)return 0;var capture=get(fitting.getClass(),"capture",fitting);if(capture==null)return 0;check((boolean)get(capture.getClass(),"closed",capture),"actual capture lease closed before measurement publication");
        return (long)get(capture.getClass(),"vertices",capture);
    }
    static void awaitPreparation(Object owner,SubmissionCaptureBackend backend)throws Exception{
        var fitting=get(owner.getClass(),"nativeFit",owner);if(fitting==null)return;
        var neutral=(PreviewModelFrames.NeutralBody)get(fitting.getClass(),"neutral",fitting);if(neutral==null)return;
        if(neutral instanceof com.naocraftlab.skins.compat.cpm.CpmNeutralBody){
            var decoded=(java.util.concurrent.CompletableFuture<?>)get(neutral.getClass(),"decoded",neutral);decoded.get(5,java.util.concurrent.TimeUnit.SECONDS);
        }
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(neutral.body().status()==PreviewRenderer.BodyStatus.PENDING&&System.nanoTime()<deadline){backend.drain();Thread.sleep(1);}
        check(neutral.body().status()!=PreviewRenderer.BodyStatus.PENDING,"native neutral preparation completes through its own producer");
    }
    static void consume(Class<?> consumer,java.lang.reflect.Method prepare,Object dispatcher,PictureInPictureRenderState state,GalleryNativePassFixture subject,TextureRegistry.TextureHandle skin,boolean finalImage)throws Exception{
        var gui=new GuiRenderState();gui.submitPicturesInPictureState(state);var ordered=new ArrayList<PictureInPictureRenderState>();gui.forEachPictureInPicture(ordered::add);check(ordered.size()==1&&ordered.get(0)==state,"native collector preserves own exact state");int start=SubmissionCaptureDevice.drawRecords.size();long bindingStart=active.materialBindings;
        try(var frame=(AutoCloseable)consumer.getMethod("open", Object.class).invoke(null, consumer)){
            invoke(prepare,dispatcher,state,gui,1);
            if(finalImage){check(active.materialBindings>bindingStart,"actual final primary native binding uses own exact source");var blits=new ArrayList<BlitRenderState>();gui.forEachElement(element->{if(element instanceof BlitRenderState value)blits.add(value);},GuiRenderState.TraverseRange.ALL);check(blits.size()==1,"own primary binding reaches native material consumer");var output=blits.get(0).textureSetup().texure0();check(SubmissionCaptureDevice.drawRecords.subList(start,SubmissionCaptureDevice.drawRecords.size()).stream().anyMatch(record->record.target()==output&&!record.sampled().isEmpty()),"own source native material reaches its owned output");check(active.textures.getTexture(net.minecraft.resources.Identifier.parse(skin.location())).getTextureView()!=null,"same own material registration remains live");}
            consumer.getMethod("submitted").invoke(null);
        }
        check(subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"native submission alone grants no common ACK");
    }
    public static void run(GalleryNativePassFixture original)throws Exception{
        if(Boolean.getBoolean("nclskins.cpm.submissionCleanup")){cleanupControl();return;}
        try(var backend=new SubmissionCaptureBackend();var profiles=CpmBehaviorProbe.nativeProfileScope();var subject=original.freshOwnedCpm();var registry=new Registry();var skins=new PreviewAssetCache<Object>(registry,TextureRegistry.TextureKind.PLAYER_SKIN,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding(),()->backend.owner instanceof PreviewRenderer<?> renderer?renderer.retainedTextures():Set.of());var capes=new PreviewAssetCache<Object>(registry,TextureRegistry.TextureKind.IMAGE,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding(),()->backend.owner instanceof PreviewRenderer<?> renderer?renderer.retainedTextures():Set.of())){
            var loader=new Loader();var type=loader.loadClass(PREFIX+"SubmissionPreviewRenderer");var owner=allocate(type);backend.owner=owner;initializeOwner(type,owner);
            var hostType=CpmPreviewScreenSlice.loadSubmissionNative(PREFIX+"NclSkinsScreen",type,Graphics.class,Baked.class);var host=hostType.getConstructor().newInstance();
            for(var field:hostType.getDeclaredFields()){Object value=switch(field.getName()){case "runtime"->subject.runtime;case "editorRenderer"->owner;case "skinTextures"->skins;case "capeTextures"->capes;case "textureRegistry"->registry;default->field.getType()==Map.class?new HashMap<>():null;};if(value!=null){field.setAccessible(true);field.set(host,value);}}
            var sync=hostType.getDeclaredMethod("synchronizePreviewAssets",ViewSpec.class);var draw=hostType.getDeclaredMethod("renderPreviews",Graphics.class,ViewSpec.class);var requestMethod=hostType.getDeclaredMethod("galleryRequest",ViewSpec.Preview.class);requestMethod.setAccessible(true);
            sync(sync,host,subject);subject.drainWorkers();backend.drain();sync(sync,host,subject);subject.drainWorkers();backend.drain();
            var desired=subject.runtime.galleryPreviewCandidate().orElseThrow();var source=subject.runtime.previewBodySource(desired);check(desired.bodySource().orElseThrow().cpmExpected(),"actual own carrier requires CPM body");
            @SuppressWarnings("unchecked")var optional=(Optional<PreviewRenderer.PreviewRequest>)requestMethod.invoke(host,desired);var own=optional.orElseThrow();
            while(!NativePlayerSkinLifecycle.isReady(own.appearance().skin().location())){var action=backend.nativeQueue.poll(5,java.util.concurrent.TimeUnit.SECONDS);check(action!=null,"actual source registration schedules native completion");action.run();}
            check(Arrays.equals(registry.registered.get(own.appearance().skin()),subject.ownedBytes(desired)),"actual cache relookup own imported pixels");
            @SuppressWarnings("unchecked")var production=(PreviewRenderer<Graphics>)owner;var registration=subject.runtime.observePreviewBody(desired,production::displayKind,()->production.confirmGalleryBody(source));production.observeBody(registration);check(production.prepareGalleryBody(source,true,subject.runtime.galleryPreviewAdmission(desired)),"real common registration prepares own native slot");
            var session=(EditorPreviewSession)get(type,"session",owner);backend.profile=(com.mojang.authlib.GameProfile)get(type,"profile",owner);backend.appearance=own.appearance();
            try(var definition=CpmBehaviorProbe.nativeOwnedBody(subject.ownedBytes(desired),CpmBehaviorProbe.nativeProfileId(backend.profile),own.appearance())){
                var dispatcherType=loader.loadClass(PREFIX+"LivePreviewRenderer");var dispatcher=dispatcherType.getConstructor(MultiBufferSource.BufferSource.class).newInstance(backend.buffers);var prepare=dispatcherType.getMethod("prepare",loader.loadClass(PREFIX+"LivePreviewRenderState"),GuiRenderState.class,int.class);var consumer=loader.loadClass("com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame");
                try{
                    int captures=0;long lastVertices=0;
                    for(int step=0;step<32&&subject.numericCreates==0;step++){
                        subject.drainWorkers();backend.drain();if(subject.numericCreates>0)break;sync(sync,host,subject);if(subject.numericCreates>0)break;draw(draw,host,subject);if(backend.submitted.isEmpty()){awaitPreparation(owner,backend);continue;}
                        if(backend.submitted.size()!=1||backend.submitted.get(0).x1()>=0) {
                            System.out.println("SUBMISSION_CAPTURE_WITNESS roots="+backend.roots+" draws="+SubmissionCaptureDevice.drawRecords.size()+" captures="+captures+" capturedVertices="+lastVertices+" creates="+subject.numericCreates+" persisted="+(subject.numericKey==null?"NO_KEY":subject.durableNumericFit().status()));
                            check(false,"actual native reference and BODY captures emit vertices");
                        }
                        consume(consumer,prepare,dispatcher,backend.submitted.get(0),subject,own.appearance().skin(),false);long current=capturedVertices(owner);if(current>0){captures++;lastVertices=current;}SubmissionCaptureDevice.signal();backend.features.endFrame();
                    }
                    subject.drainWorkers();backend.drain();sync(sync,host,subject);
                    System.out.println("SUBMISSION_CAPTURE_WITNESS roots="+backend.roots+" draws="+SubmissionCaptureDevice.drawRecords.size()+" captures="+captures+" capturedVertices="+lastVertices+" creates="+subject.numericCreates+" persisted="+(subject.numericKey==null?"NO_KEY":subject.durableNumericFit().status()));
                    check(captures>=2&&lastVertices>0&&backend.roots>=2,"actual native reference and BODY captures emit vertices");check(subject.numericCreates==1&&subject.durableNumericFit().status()==PreviewModelFitStore.Status.PRESENT,"actual numeric first-create and fresh durable readback");check(!session.imageVisible()&&subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"measurement publication is not own image or ACK");
                    draw(draw,host,subject);check(backend.submitted.size()==1&&backend.submitted.get(0).x1()<0,"own resolved image remains hidden before consumption");consume(consumer,prepare,dispatcher,backend.submitted.get(0),subject,own.appearance().skin(),true);check(!session.imageVisible(),"hidden image waits for its own fence");SubmissionCaptureDevice.signal();backend.features.endFrame();check(session.imageVisible()&&subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"hidden own consumer fence permits image but not controls");
                    draw(draw,host,subject);check(backend.submitted.size()==1&&backend.submitted.get(0).x0()>=0,"own hidden material progresses to visible native pass");consume(consumer,prepare,dispatcher,backend.submitted.get(0),subject,own.appearance().skin(),true);SubmissionCaptureDevice.signal();backend.features.endFrame();check(subject.displayed()==PreviewRenderer.DisplayKind.CPM&&subject.preview().bodySource().equals(desired.bodySource()),"visible own consumer fence grants current common CPM ACK");
                    subject.layersVisible(false);invoke(hostType.getDeclaredMethod("releasePreviewAccessories"),host);invoke(hostType.getDeclaredMethod("releasePreviewBody"),host);
                }finally{((AutoCloseable)dispatcher).close();}
            }finally{((AutoCloseable)owner).close();SubmissionCaptureDevice.signal();}
        }
        System.out.println("PASS SUBMISSION_CPM actual native reference/BODY/numeric -> own hidden image/material/fence -> visible image/fence/common ACK");
    }

    private AutoCloseable neoEvents;

    static void cleanupControl()throws Exception {
        var backend=new SubmissionCaptureBackend();
        var events=backend.neoEvents;
        backend.neoEvents=()->{if(events!=null)events.close();throw new IllegalStateException("synthetic unregister failure");};
        Throwable observed=null;
        try{backend.close();}catch(Throwable failure){observed=failure;}
        check(observed instanceof IllegalStateException&&observed.getMessage().equals("synthetic unregister failure")&&observed.getSuppressed().length==0,"exact unregister failure with clean remaining teardown");
        check(active==null&&get(Minecraft.class,"instance",null)==backend.oldClient,"unregister failure restores native client and owner");
        for(var entry:backend.previous.entrySet())check(get(RenderSystem.class,entry.getKey(),null)==entry.getValue(),"unregister failure restores every borrowed render binding");
        check(com.tom.cpm.shared.MinecraftClientAccess.get()==backend.oldAccess&&get(com.tom.cpm.client.ClientBase.class,"mc",null)==backend.oldClientBase,"unregister failure restores upstream host");
        for(var texture:SubmissionCaptureDevice.textures)check(texture.isClosed(),"unregister failure closes owned textures");
        for(var buffer:SubmissionCaptureDevice.buffers)check(buffer.isClosed(),"unregister failure closes owned buffers");
        System.out.println("PASS SUBMISSION_CLEANUP exact unregister failure restores borrowed state and closes owned resources");
    }

    @Override public void close()throws Exception{
        if(closed)return;closed=true;
        try(var cleanup=new FixtureCleanup()) {
            cleanup.attempt(()->{if(features!=null)features.close();});
            cleanup.attempt(()->{if(bytes!=null)bytes.close();});
            cleanup.attempt(()->{if(textures!=null)textures.close();});
            cleanup.attempt(()->{if(target!=null)target.destroyBuffers();});
            cleanup.attempt(()->{var uniforms=get(RenderSystem.class,"dynamicUniforms",null);if(uniforms instanceof AutoCloseable value&&uniforms!=previous.get("dynamicUniforms"))value.close();});
            for(var view:SubmissionCaptureDevice.views)cleanup.attempt(()->{if(!view.isClosed())view.close();});
            for(var texture:SubmissionCaptureDevice.textures)cleanup.attempt(()->{if(!texture.isClosed())texture.close();});
            for(var buffer:SubmissionCaptureDevice.buffers)cleanup.attempt(()->{if(!buffer.isClosed())buffer.close();});
            var events=neoEvents;neoEvents=null;cleanup.attempt(()->{if(events!=null)events.close();});
            cleanup.attempt(()->{if(upstreamInitialized)com.tom.cpm.shared.MinecraftObjectHolder.setClientObject(oldAccess);});
            cleanup.attempt(()->{if(nativeClient!=null)set(com.tom.cpm.client.ClientBase.class,"manager",nativeClient,oldManager);});
            cleanup.attempt(()->{if(upstreamInitialized)set(com.tom.cpm.client.ClientBase.class,"mc",null,oldClientBase);});
            cleanup.attempt(()->{if(nativeClient!=oldNativeClient)set(Class.forName("com.tom.cpm.client.CustomPlayerModelsClient"),"INSTANCE",null,oldNativeClient);});
            for(var entry:previous.entrySet())cleanup.attempt(()->set(RenderSystem.class,entry.getKey(),null,entry.getValue()));
            cleanup.attempt(()->set(Minecraft.class,"instance",null,oldClient));
            active=null;
        }
    }
}
