package com.naocraftlab.skins.probe;

import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import com.naocraftlab.skins.compat.client.identifier.IdentifierTextureRegistry;
import com.unascribed.ears.api.features.EarsFeatures;
import com.unascribed.ears.api.features.EarsFeatures.WingAnimationMode;
import diy.y2k.five.thermite.EarsTexture;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.*;
import net.minecraft.core.ClientAsset;
import java.nio.file.*;

public final class EarsJoinBridge {
    private static Processed selected;
    private static boolean neutral=true;
    public record Processed(IdentifierTextureRegistry registry,EarsTexture texture,Identifier id,EarsProbe.Observation observation) implements AutoCloseable {
        public byte[] content(){return EarsProbe.sourceContents.get(id).clone();}
        public boolean current(){return observation.current()&&NativePlayerSkinLifecycle.isReady(id.toString());}
        public int[] pixels(){
            var adapter=new diy.y2k.five.thermite.NativeImageAdapter(texture.getPixels());
            int[] result=new int[4096];for(int y=0;y<64;y++)for(int x=0;x<64;x++)result[y*64+x]=adapter.getARGB(x,y);return result;
        }
        public void close(){registry.close();}
    }
    public static Processed process(Path path) throws Exception {
        var registry=EarsProbe.start(Files.readAllBytes(path));
        var id=EarsProbe.source;
        if(NativePlayerSkinLifecycle.isReady(id.toString())||EarsProbe.observe(id)!=null)throw new AssertionError("source must initially await real processing");
        EarsProbe.queued().run();
        var texture=(EarsTexture)EarsProbe.Host.INSTANCE.textures.map.get(id);
        var observed=EarsProbe.observe(id);
        if(observed==null||!observed.current()||!NativePlayerSkinLifecycle.isReady(id.toString()))throw new AssertionError("actual processed own texture/ticket required");
        var features=texture.earsFeatures();
        if(!features.enabled||features.earMode!=EarsFeatures.EarMode.TALL||features.wingMode!=EarsFeatures.WingMode.SYMMETRIC_DUAL||features.tailSegments!=3||!features.capeEnabled)throw new AssertionError("real native processing preserves combined authored Ears");
        System.out.println("NATIVE_EARS_PROCESSED ready=true ear="+features.earMode+" wing="+features.wingMode+" tailSegments="+features.tailSegments+" cape="+features.capeEnabled);
        selected=new Processed(registry,texture,id,observed);return selected;
    }
    public static void use(Processed source,boolean authoredNeutral){selected=source;neutral=authoredNeutral;}
    public static Processed processed(IdentifierTextureRegistry registry,Identifier id){
        var texture=EarsProbe.Host.INSTANCE.textures.map.get(id);
        var observation=EarsProbe.observe(id);
        if(!(texture instanceof EarsTexture ears)||observation==null||!observation.current()||!NativePlayerSkinLifecycle.isReady(id.toString()))throw new AssertionError("genuine own processing required");
        return new Processed(registry,ears,id,observation);
    }
    public static EarsFeatures features(AvatarRenderState state){
        if(selected==null)return EarsFeatures.DISABLED;
        if(!selected.current()||!state.skin.body().texturePath().equals(selected.id()))throw new AssertionError("exact current installed Ears source");
        var live=selected.texture().earsFeatures();
        return neutral?EarsFeatures.copyOf(live).animateTail(false).wingAnimationMode(WingAnimationMode.NONE).capeEnabled(false).build():live;
    }
    public static void prepare(AvatarRenderState state){
        if(selected!=null){
            state.skin=new PlayerSkin(new ClientAsset.DownloadedTexture(selected.id(),"synthetic:no-network"),null,null,PlayerModelType.WIDE,false);
            EarsProbe.drawingObservation=selected.observation();
        }
        EarsProbe.selected=features(state);
    }
    public static void material(net.minecraft.client.renderer.rendertype.RenderType type){
        if(!EarsProbe.materials.containsKey(type))throw new AssertionError("genuine Ears material acquisition terminal");
    }
    public static void clear(){selected=null;EarsProbe.selected=EarsFeatures.DISABLED;EarsProbe.drawingObservation=null;}
}
