package com.naocraftlab.skins.buildlogic.probe;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.naocraftlab.skins.compat.client.identifier.measurement.AvatarBodyCapture;
import com.naocraftlab.skins.compat.cpm.CpmIntegration;
import com.tom.cpm.client.ClientBase;
import com.tom.cpm.client.MinecraftObject;
import com.tom.cpm.client.PlayerRenderManager;
import com.tom.cpm.client.PlayerRenderStateAccess;
import com.tom.cpm.shared.config.Player;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.resources.Identifier;

public final class CpmNativeMeasurementBackend {
    static final Map<String,Object> previous = new LinkedHashMap<>();
    static Minecraft minecraft;
    static TextureManager textures;
    static PlayerRenderManager manager;
    static Avatar avatar;
    static ByteBufferBuilder bytes;
    static MultiBufferSource.BufferSource buffers;
    static SubmitNodeStorage storage;
    static FeatureRenderDispatcher features;
    static com.tom.cpm.shared.MinecraftClientAccess originalHost;
    static AutoCloseable loaderEvents;
    static final Set<String> installed = new HashSet<>();
    static Player<?> selectedPlayer;
    static int selections;
    static boolean liveBody;
    static final Set<Object> borrowedHardware=Collections.newSetFromMap(new IdentityHashMap<>());

    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static Object allocate(Class<?> type) throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return ((sun.misc.Unsafe)field.get(null)).allocateInstance(type);
    }
    static Object get(Class<?> type, String name, Object owner) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    static void set(Class<?> type, String name, Object owner, Object value) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); field.set(owner,value);
    }
    static EntityRendererProvider.Context context() {
        var roots = com.google.common.collect.ImmutableMap.<net.minecraft.client.model.geom.ModelLayerLocation,net.minecraft.client.model.geom.builders.LayerDefinition>builder();
        var cube = net.minecraft.client.model.geom.builders.CubeDeformation.NONE;
        var layer = net.minecraft.client.model.geom.builders.LayerDefinition.class;
        net.minecraft.client.model.geom.ModelLayers.PLAYER_ARMOR.putFrom(net.minecraft.client.model.player.PlayerModel.createArmorMeshSet(new net.minecraft.client.model.geom.builders.CubeDeformation(0.5F),new net.minecraft.client.model.geom.builders.CubeDeformation(1)).map(mesh -> net.minecraft.client.model.geom.builders.LayerDefinition.create(mesh,64,32)),roots);
        roots.put(net.minecraft.client.model.geom.ModelLayers.PLAYER,net.minecraft.client.model.geom.builders.LayerDefinition.create(net.minecraft.client.model.player.PlayerModel.createMesh(cube,false),64,64));
        roots.put(net.minecraft.client.model.geom.ModelLayers.ARROW,net.minecraft.client.model.object.projectile.ArrowModel.createBodyLayer());
        roots.put(net.minecraft.client.model.geom.ModelLayers.BEE_STINGER,net.minecraft.client.model.animal.bee.BeeStingerModel.createBodyLayer());
        roots.put(net.minecraft.client.model.geom.ModelLayers.PLAYER_EARS,net.minecraft.client.model.player.PlayerEarsModel.createEarsLayer());
        roots.put(net.minecraft.client.model.geom.ModelLayers.PLAYER_CAPE,net.minecraft.client.model.player.PlayerCapeModel.createCapeLayer());
        var elytra=net.minecraft.client.model.object.equipment.ElytraModel.createLayer();
        roots.put(net.minecraft.client.model.geom.ModelLayers.ELYTRA,elytra);
        roots.put(net.minecraft.client.model.geom.ModelLayers.ELYTRA_BABY,elytra.apply(net.minecraft.client.model.object.equipment.ElytraModel.BABY_TRANSFORMER));
        roots.put(net.minecraft.client.model.geom.ModelLayers.PARROT,net.minecraft.client.model.animal.parrot.ParrotModel.createBodyLayer());
        roots.put(net.minecraft.client.model.geom.ModelLayers.PLAYER_SPIN_ATTACK,net.minecraft.client.model.effects.SpinAttackEffectModel.createLayer());
        return new EntityRendererProvider.Context(null,null,null,null,null,new net.minecraft.client.model.geom.EntityModelSet(roots.buildOrThrow()),new net.minecraft.client.resources.model.EquipmentAssetManager(),null,null,null);
    }
    static final class Avatar extends net.minecraft.client.renderer.entity.player.AvatarRenderer<net.minecraft.client.player.RemotePlayer> {
        Avatar(EntityRendererProvider.Context context) { super(context,false); }
        int layers() { return layers.size(); }
        @Override protected net.minecraft.client.renderer.rendertype.RenderType getRenderType(AvatarRenderState state,boolean visible,boolean translucent,boolean outline) {
            var selected=com.naocraftlab.skins.compat.cpm.CpmNeutralBody.select(selectedPlayer,selectedPlayer.getModelDefinition0());
            check(selected==null ? !manager.isBound(getModel())
                    : manager.getHolderSafe(getModel(),null,h -> h.swappedIn && h.def==selected
                            && h.def instanceof com.naocraftlab.skins.compat.cpm.OwnedCpmDefinition
                            && ((com.naocraftlab.skins.compat.cpm.OwnedCpmDefinition)h.def).source()==CpmIntegration.source(selectedPlayer),false,false),
                    "genuine material selects exact neutral/ordinary source, not a live substitute");
            selections++;
            return super.getRenderType(state,visible,translucent,outline);
        }
        @Override protected void submitNameDisplay(AvatarRenderState state,PoseStack pose,SubmitNodeCollector collector,net.minecraft.client.renderer.state.level.CameraRenderState camera) {}
    }
    public static AutoCloseable liveScope() { boolean previous=liveBody;liveBody=true;return () -> liveBody=previous; }
    public static AutoCloseable scope() {
        boolean[] closed={false};
        return ()->{if(!closed[0]){closed[0]=true;close();}};
    }
    public static void install() throws Exception {
        if(features!=null)return;
        originalHost=com.tom.cpm.shared.MinecraftClientAccess.get();
        borrowedHardware.clear();borrowedHardware.addAll(SubmissionCpuBackend.views);borrowedHardware.addAll(SubmissionCpuBackend.textures);borrowedHardware.addAll(SubmissionCpuBackend.buffers);
        previous.put("client",get(Minecraft.class,"instance",null));
        for(String field:List.of("DEVICE","renderThread","dynamicUniforms","projectionMatrixBuffer","projectionType","outputColorTextureOverride","outputDepthTextureOverride")) previous.put(field,get(RenderSystem.class,field,null));
        var device=SubmissionCpuBackend.device();
        set(RenderSystem.class,"DEVICE",null,device);set(RenderSystem.class,"renderThread",null,Thread.currentThread());set(RenderSystem.class,"dynamicUniforms",null,new DynamicUniforms());
        minecraft=(Minecraft)allocate(Minecraft.class);set(Minecraft.class,"instance",null,minecraft);
        var level=(LevelRenderer)allocate(LevelRenderer.class);set(LevelRenderer.class,"targets",level,allocate(LevelTargetBundle.class));set(Minecraft.class,"levelRenderer",minecraft,level);
        var game=(GameRenderer)allocate(GameRenderer.class);set(Minecraft.class,"gameRenderer",minecraft,game);set(GameRenderer.class,"overlayTexture",game,new OverlayTexture());
        var light=(Lightmap)allocate(Lightmap.class);var lt=device.createTexture("readonly CPU light",com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING,com.mojang.blaze3d.textures.TextureFormat.RGBA8,16,16,1,1);set(Lightmap.class,"texture",light,lt);set(Lightmap.class,"textureView",light,device.createTextureView(lt));set(GameRenderer.class,"lightmap",game,light);
        textures=(TextureManager)allocate(TextureManager.class);set(TextureManager.class,"byPath",textures,new HashMap<Identifier,AbstractTexture>());set(TextureManager.class,"tickableTextures",textures,new HashSet<>());set(Minecraft.class,"textureManager",minecraft,textures);
        for(String resource:List.of("white.png","template/cape.png")) try(var input=CpmNativeMeasurementBackend.class.getClassLoader().getResourceAsStream("assets/cpm/textures/"+resource)) {
            check(input!=null,"public CPM material resource");
            textures.register(Identifier.parse("cpm:textures/"+resource),new DynamicTexture(()->"public CPM material",com.mojang.blaze3d.platform.NativeImage.read(input)));
        }
        set(Minecraft.class,"mainRenderTarget",minecraft,new com.mojang.blaze3d.pipeline.TextureTarget("measurement CPU target",160,180,true));
        originalHost=com.tom.cpm.shared.MinecraftClientAccess.get();MinecraftObject mc;
        try { mc=new MinecraftObject();manager=mc.getPlayerRenderManager();ClientBase.mc=mc; }
        finally { com.tom.cpm.shared.MinecraftObjectHolder.setClientObject(originalHost); }
        var cls=Class.forName("com.tom.cpm.client.CustomPlayerModelsClient");Object client=cls.getField("INSTANCE").get(null);if(client==null){client=cls.getConstructor().newInstance();cls.getField("INSTANCE").set(null,client);}ClientBase.class.getField("manager").set(client,new com.tom.cpm.shared.model.RenderManager(manager,null,java.util.function.Function.identity()));
        if("neoforge".equals(System.getProperty("nclskins.upstream.family")))loaderEvents=(AutoCloseable)Class.forName("com.naocraftlab.skins.buildlogic.probe.NeoImmediateEvents").getConstructor(Object.class).newInstance(client);
        com.tom.cpm.shared.MinecraftObjectHolder.setClientObject((com.tom.cpm.shared.MinecraftClientAccess)java.lang.reflect.Proxy.newProxyInstance(CpmNativeMeasurementBackend.class.getClassLoader(),new Class<?>[]{com.tom.cpm.shared.MinecraftClientAccess.class},(p,m,a)->m.getName().equals("createTexture")?mc.createTexture():m.invoke(originalHost,a)));
        avatar=new Avatar(context());check(avatar.layers()==11,"genuine native renderer has eleven layers");
        bytes=new ByteBufferBuilder(65536);buffers=MultiBufferSource.immediate(bytes);storage=new SubmitNodeStorage();
        var models=(net.minecraft.client.resources.model.ModelManager)allocate(net.minecraft.client.resources.model.ModelManager.class);set(net.minecraft.client.resources.model.ModelManager.class,"blockStateModelSet",models,new net.minecraft.client.renderer.block.BlockStateModelSet(Map.of(),null));
        features=new FeatureRenderDispatcher(storage,models,buffers,null,null,buffers,null,new net.minecraft.client.renderer.state.GameRenderState());
        CpmExtractionBodyProbe.measurement=CpmNativeMeasurementBackend::measure;
    }
    static boolean measure(CpmExtractionBodyProbe.Avatar raw) {
        return CpmNativeMeasurementCore.measure(raw,liveBody,textures,avatar.getModel(),installed,(player,state)-> {
            selectedPlayer=player;selections=0;
            ReferenceBodyProbe.reset();
            avatar.submit(state,new PoseStack(),storage,new net.minecraft.client.renderer.state.level.CameraRenderState());
            check(selections>0,"genuine native material selection reached the exact-source observer");
            boolean collecting=AvatarBodyCapture.collecting();
            features.renderAllFeatures();buffers.endBatch();features.endFrame();SubmissionCpuBackend.signal();
            if (AvatarBodyCapture.excludes(raw)) System.out.println("NATIVE_CAPTURE_WITNESS collecting="+collecting+" meshVertices="+ReferenceBodyProbe.verticesCount);
        });
    }
    public static void main(String[] args) throws Exception {
        CpmNativeMeasurementCore.stateBinding(java.nio.file.Path.of(args[0]));
    }
    static void close() throws Exception {
        if(previous.isEmpty())return;
        var saved=new LinkedHashMap<>(previous);previous.clear();
        var events=loaderEvents;loaderEvents=null;
        var ownedFeatures=features;features=null;
        var ownedBytes=bytes;bytes=null;
        var ownedTextures=textures;textures=null;
        var ownedMinecraft=minecraft;minecraft=null;
        var host=originalHost;
        var installedHost=com.tom.cpm.shared.MinecraftClientAccess.get();
        var views=new ArrayList<>(SubmissionCpuBackend.views);
        var hardwareTextures=new ArrayList<>(SubmissionCpuBackend.textures);
        var hardwareBuffers=new ArrayList<>(SubmissionCpuBackend.buffers);
        views.removeAll(borrowedHardware);hardwareTextures.removeAll(borrowedHardware);hardwareBuffers.removeAll(borrowedHardware);
        var bindings=new LinkedHashMap<String,Object>();
        CpmExtractionBodyProbe.measurement=ignored->false;
        CpmExtractionBodyProbe.measurementPlayers.clear();installed.clear();
        try(var cleanup=new FixtureCleanup()) {
            for(var entry:saved.entrySet())if(!entry.getKey().equals("client"))cleanup.attempt(()->bindings.put(entry.getKey(),get(RenderSystem.class,entry.getKey(),null)));
            cleanup.attempt(()->{if(events!=null)events.close();});
            cleanup.attempt(()->{if(ownedFeatures!=null)ownedFeatures.close();});
            cleanup.attempt(()->{if(ownedBytes!=null)ownedBytes.close();});
            cleanup.attempt(()->{if(ownedTextures!=null)ownedTextures.close();});
            cleanup.attempt(()->{if(ownedMinecraft!=null&&ownedMinecraft.gameRenderer!=null&&ownedMinecraft.gameRenderer.overlayTexture()!=null)ownedMinecraft.gameRenderer.overlayTexture().close();});
            cleanup.attempt(()->{if(ownedMinecraft!=null&&ownedMinecraft.getMainRenderTarget()!=null)ownedMinecraft.getMainRenderTarget().destroyBuffers();});
            cleanup.attempt(()->{Object uniforms=bindings.get("dynamicUniforms");if(uniforms instanceof AutoCloseable owned&&uniforms!=saved.get("dynamicUniforms"))owned.close();});
            for(var view:views)cleanup.attempt(()->{if(!view.isClosed())view.close();});
            for(var texture:hardwareTextures)cleanup.attempt(()->{if(!texture.isClosed())texture.close();});
            for(var buffer:hardwareBuffers)cleanup.attempt(()->{if(!buffer.isClosed())buffer.close();});
            for(var entry:saved.entrySet())if(!entry.getKey().equals("client"))cleanup.attempt(()->{
                if(bindings.containsKey(entry.getKey())&&get(RenderSystem.class,entry.getKey(),null)==bindings.get(entry.getKey()))set(RenderSystem.class,entry.getKey(),null,entry.getValue());
            });
            cleanup.attempt(()->{if(get(Minecraft.class,"instance",null)==ownedMinecraft)set(Minecraft.class,"instance",null,saved.get("client"));});
            cleanup.attempt(()->{if(com.tom.cpm.shared.MinecraftClientAccess.get()==installedHost&&installedHost!=host)com.tom.cpm.shared.MinecraftObjectHolder.setClientObject(host);});
        }
    }
}
