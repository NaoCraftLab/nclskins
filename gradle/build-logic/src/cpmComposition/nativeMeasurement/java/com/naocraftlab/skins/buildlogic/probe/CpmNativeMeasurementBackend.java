package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import java.util.*;
import com.naocraftlab.skins.compat.client.identifier.measurement.AvatarBodyCapture;
import com.naocraftlab.skins.compat.cpm.*;
import com.tom.cpm.client.*;
import com.tom.cpm.shared.config.Player;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.pip.*;
import net.minecraft.client.renderer.texture.*;
import static com.naocraftlab.skins.buildlogic.probe.PipMaterialProbe.*;

public final class CpmNativeMeasurementBackend {
    static FeatureRenderDispatcher features;
    static final Set<String> installed = new HashSet<>();
    static Player<?> selectedPlayer;
    static int selections;
    static PipProcessState processState;
    static com.tom.cpm.shared.MinecraftClientAccess originalHost;
    static boolean liveBody;
    static AbstractTexture white;
    static boolean negativeInjected;
    static final net.minecraft.resources.Identifier WHITE=net.minecraft.resources.Identifier.parse("cpm:textures/white.png");
    static final class ObservedTextures extends TextureManager {
        final List<AbstractTexture> whiteSelections=new ArrayList<>();
        ObservedTextures() { super(null); }
        @Override public AbstractTexture getTexture(net.minecraft.resources.Identifier id) {
            var result=super.getTexture(id);
            if(id.equals(WHITE))whiteSelections.add(result);
            return result;
        }
    }
    public static AutoCloseable liveScope() {
        boolean previous=liveBody;liveBody=true;return () -> liveBody=previous;
    }
    public static AutoCloseable scope() {
        boolean[] closed={false};
        return () -> {
            if(closed[0])return;closed[0]=true;
            var state=processState;
            if(state==null)return;
            var ownedFeatures=features;var ownedVertices=vertices;
            processState=null;features=null;
            CpmExtractionBodyProbe.measurement=ignored->false;
            CpmExtractionBodyProbe.measurementPlayers.clear();
            try(var cleanup=new FixtureCleanup()) {
                cleanup.attempt(state::prepareClose);
                cleanup.attempt(()->{if(ownedFeatures!=null)ownedFeatures.close();});
                cleanup.attempt(()->{if(ownedVertices!=null)ownedVertices.close();});
                cleanup.attempt(state::close);
            }
        };
    }
    public static void materialSelection(AvatarRenderState state) {
        var definition = selectedPlayer.getModelDefinition0();
        var selected = CpmNeutralBody.select(selectedPlayer, definition);
        check(selected == null ? !manager.isBound(avatar.getModel())
                : manager.getHolderSafe(avatar.getModel(),null,h -> h.swappedIn && h.def == selected && h.def instanceof OwnedCpmDefinition
                        && ((OwnedCpmDefinition)h.def).source() == CpmIntegration.source(selectedPlayer),false,false),
                "genuine measurement material binds exact neutral/ordinary definition, never live substitute");
        selections++;
    }
    public static void install() throws Exception {
        if (features != null) return;
        processState = new PipProcessState();
        host();
        textures=new ObservedTextures();
        set(net.minecraft.client.Minecraft.class,"textureManager",minecraft,textures);
        try (var input=CpmNativeMeasurementBackend.class.getClassLoader().getResourceAsStream("assets/cpm/textures/white.png")) {
            check(input!=null,"public CPM white material resource");
            white=new DynamicTexture(()->"public CPM white material",com.mojang.blaze3d.platform.NativeImage.read(input));
            textures.register(WHITE,white);
        }
        var previous = com.tom.cpm.shared.MinecraftClientAccess.get();
        originalHost = previous;
        var mc = new MinecraftObject(); manager = mc.getPlayerRenderManager(); ClientBase.mc = mc;
        com.tom.cpm.shared.MinecraftObjectHolder.setClientObject(previous);
        var clientClass = Class.forName("com.tom.cpm.client.CustomPlayerModelsClient");
        Object client = clientClass.getField("INSTANCE").get(null);
        if (client == null) { client = clientClass.getConstructor().newInstance(); clientClass.getField("INSTANCE").set(null, client); }
        ClientBase.class.getField("manager").set(client, new com.tom.cpm.shared.model.RenderManager(manager, null, java.util.function.Function.identity()));
        if (System.getProperty("nclskins.upstream.family").equals("neoforge")) processState.installNeoEvents(client);
        com.tom.cpm.shared.MinecraftObjectHolder.setClientObject((com.tom.cpm.shared.MinecraftClientAccess)java.lang.reflect.Proxy.newProxyInstance(CpmNativeMeasurementBackend.class.getClassLoader(), new Class<?>[]{com.tom.cpm.shared.MinecraftClientAccess.class}, (p,m,a) -> m.getName().equals("createTexture") ? mc.createTexture() : m.invoke(previous,a)));
        avatar = new Avatar((net.minecraft.client.renderer.entity.EntityRendererProvider.Context)call(CpmCurrentNativeGeometryProbe.class,"rendererContext",new Class<?>[]{},null));
        check(avatar.layerCount() == 11, "measurement uses eleven genuine native layers"); rawRenderer();
        var buffers = (RenderBuffers)allocate(RenderBuffers.class); set(RenderBuffers.class,"stagedVertexBuffer",buffers,vertices);
        features = new FeatureRenderDispatcher(buffers,null,null,null,new net.minecraft.client.renderer.state.GameRenderState());
        processState.captureOwned();
        CpmExtractionBodyProbe.measurement = CpmNativeMeasurementBackend::measure;
    }
    static boolean measure(CpmExtractionBodyProbe.Avatar raw) {
        return CpmNativeMeasurementCore.measure(raw,liveBody,textures,avatar.getModel(),installed,(player,state)-> {
            selectedPlayer = player; selections = 0;
            com.naocraftlab.skins.probe.EarsJoinBridge.prepare(state);
            ReferenceBodyProbe.reset();
            var pip = new net.minecraft.client.gui.render.pip.GuiEntityRenderer(minecraft.getEntityRenderDispatcher());
            var gui = new GuiRenderState();
            var input = new GuiEntityRenderState(state,new org.joml.Vector3f(),new org.joml.Quaternionf().rotateZ((float)Math.PI),new org.joml.Quaternionf(),-128,-256,-64,-128,64,null);
            var observed=(ObservedTextures)textures;
            check(minecraft.getTextureManager()==observed,"selected renderer uses exact owned native texture manager");
            @SuppressWarnings("unchecked") var nativeMaterials=(Map<net.minecraft.resources.Identifier,AbstractTexture>)get(TextureManager.class,"byPath",textures);
            String mutation=System.getProperty("nclskins.measurement.whiteMutation","");
            boolean mutate=!mutation.isEmpty()&&!negativeInjected&&CpmNeutralBody.select(player,player.getModelDefinition0())!=null;
            if(mutate) {
                check(mutation.equals("missing")||mutation.equals("foreign"),"known white material sensitivity mode");
                check(nativeMaterials.get(WHITE)==white,"sensitivity starts from exact genuine material");
                if(mutation.equals("missing"))nativeMaterials.remove(WHITE);
                else {
                    var foreign=nativeMaterials.get(net.minecraft.resources.Identifier.parse(CpmIntegration.source(player).location()));
                    check(foreign!=null&&foreign!=white,"distinct live owned skin material for foreign control");
                    nativeMaterials.put(WHITE,foreign);
                }
                negativeInjected=true;
            }
            int selectionStart=observed.whiteSelections.size();
            int drawStart=PipCpuBackend.drawRecords.size();
            try (AutoCloseable restore=()-> {
                if(mutate) {
                    var replaced=nativeMaterials.put(WHITE,white);
                    if(mutation.equals("missing")&&replaced!=null&&replaced!=white)replaced.close();
                }
            }) { pip.prepare(input,gui,features,1); }
            var blits = new ArrayList<BlitRenderState>(); gui.forEachElement(e -> { if(e instanceof BlitRenderState b)blits.add(b); },GuiRenderState.TraverseRange.ALL);
            check(blits.size()==1 && ReferenceBodyProbe.verticesCount>0 && selections>0,"actual hidden native prepare and exact-source mesh drain");
            var whiteSelections=observed.whiteSelections.subList(selectionStart,observed.whiteSelections.size());
            boolean whiteValid=true;
            if(!whiteSelections.isEmpty()) {
                var output=blits.get(0).textureSetup().texure0();
                boolean bound=PipCpuBackend.drawRecords.subList(drawStart,PipCpuBackend.drawRecords.size()).stream().anyMatch(d->d.target()==output&&d.sampled().stream().anyMatch(v->v==white.getTextureView()));
                System.out.println("CPM_WHITE_NATIVE_CONSUMER selections="+whiteSelections.size()+" vertices="+ReferenceBodyProbe.verticesCount+" bound="+bound);
                whiteValid=whiteSelections.stream().allMatch(t->t==white)&&bound;
            }
            PipMatrix.consume(blits.get(0)); vertices.endFrame(); PipCpuBackend.signal(); pip.close();
            check(whiteValid,"actual selected CPM white material must reach native target sampler");
            System.out.println("CANONICAL_NATIVE_MEASUREMENT vertices="+ReferenceBodyProbe.verticesCount);
        });
    }
    public static void main(String[] args) throws Exception {
        CpmNativeMeasurementCore.stateBinding(Path.of(args[0]));
        String avatarOwner="com/naocraftlab/skins/buildlogic/probe/PipMaterialProbe$Avatar";
        var node=new org.objectweb.asm.tree.ClassNode();
        try(var in=ClassLoader.getSystemResourceAsStream(avatarOwner+".class")){new org.objectweb.asm.ClassReader(in).accept(node,0);}
        int hooks=0;
        for(var m:node.methods)for(var ins:m.instructions.toArray())if(ins instanceof org.objectweb.asm.tree.MethodInsnNode call
                && call.name.equals("materialSelection") && call.owner.equals("com/naocraftlab/skins/buildlogic/probe/ReferenceBodyProbe")){
            call.owner=org.objectweb.asm.Type.getInternalName(CpmNativeMeasurementBackend.class);hooks++;
        }
        check(hooks==1,"one native selected-material observer");
        var output=new org.objectweb.asm.ClassWriter(0);node.accept(output);var avatarFile=Path.of(args[0],avatarOwner+".class");Files.write(avatarFile,output.toByteArray());
    }
}
