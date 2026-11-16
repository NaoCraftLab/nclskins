package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.runtime.*;
import com.naocraftlab.skins.probe.CardTextureBridge;
import java.util.*;
import java.lang.reflect.Method;
import static com.naocraftlab.skins.buildlogic.probe.PipMaterialProbe.check;

public final class BakedCardConsumer implements AutoCloseable {
    public static final List<Baked> created=new ArrayList<>();
    private static final List<Baked> drawn=new ArrayList<>();
    public static final class Baked implements PreviewRenderer<ReferenceBodyHost.Graphics>, AutoCloseable {
        final CpmBakedDisplayProbe.SelectedFallback nativeConsumer;
        PreviewRenderer.PreviewRequest request;
        PreviewRenderer.BodyStatus completionStatus;
        public Baked() {
            try {nativeConsumer=new CpmBakedDisplayProbe.SelectedFallback();created.add(this);}
            catch(Exception failure){throw new AssertionError(failure);}
        }
        public void render(ReferenceBodyHost.Graphics graphics,PreviewRenderer.PreviewRequest value) {
            request=value;
            drawn.add(this);
            nativeConsumer.render(value,new PreviewModelFrames.Completion() {
                public boolean current(){return true;}
                public void accept(PreviewModelFrames.Body body){completionStatus=body.status();}
            });
        }
        public void close(){nativeConsumer.close();}
    }
    final Object host;
    final Method sync,draw;
    final PreviewAssetCache<Object> skins,capes;
    final CardTextureBridge textures=new CardTextureBridge();
    public BakedCardConsumer(ClientRuntime runtime) throws Exception {
        var type=CpmPreviewScreenSlice.loadNative(ReferenceBodyHost.PREFIX+"NclSkinsScreen",ReferenceBodyHost.renderer.getClass(),ReferenceBodyHost.Graphics.class,Baked.class);
        host=type.getConstructor().newInstance();
        skins=new PreviewAssetCache<>(textures,TextureRegistry.TextureKind.PLAYER_SKIN,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
        capes=new PreviewAssetCache<>(textures,TextureRegistry.TextureKind.IMAGE,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
        for(var field:type.getDeclaredFields()) {
            Object value=switch(field.getName()) {
                case "runtime" -> runtime;
                case "editorRenderer" -> ReferenceBodyHost.renderer;
                case "skinTextures" -> skins;
                case "capeTextures" -> capes;
                case "textureRegistry" -> new CpmOwnedPreviewDispatchProbe.Registry();
                default -> field.getType()==Map.class?new HashMap<>():null;
            };
            if(value!=null)field.set(host,value);
        }
        sync=type.getDeclaredMethod("syncPreviewAssets",ViewSpec.class);sync.setAccessible(true);
        draw=type.getDeclaredMethod("renderStaticPreviews",ReferenceBodyHost.Graphics.class,ViewSpec.class);draw.setAccessible(true);
    }
    public void sync(ViewSpec view) throws Exception {sync.invoke(host,view);}
    public List<Baked> draw(ViewSpec view) throws Exception {
        drawn.clear();draw.invoke(host,new ReferenceBodyHost.Graphics(),view);
        return List.copyOf(drawn);
    }
    public Map<String,Object> consume(Baked value, String role, ViewSpec.Preview preview) throws Exception {
        var request=value.request;var appearance=request.appearance();
        textures.registered(appearance.skin().location());
        check(appearance.model()==(preview.variant()==com.naocraftlab.skins.core.model.SkinVariant.SLIM?SkinModel.SLIM:SkinModel.CLASSIC)
                && appearance.outerLayerVisibility().equals(preview.outerLayerVisibility()) && appearance.cape().isEmpty(),"compiled card owns model/mask/no foreign cape");
        value.nativeConsumer.consume();
        var nativePass=value.nativeConsumer;
        check(nativePass.primaryDraws==1 && nativePass.materialPasses==1 && nativePass.completions==1
                && nativePass.source.equals(appearance.skin().location()),"actual loader/cache/host native own-source material/completion "+role);
        double baseline=0.97*request.height()/2.125*request.scale();
        check(Math.abs(nativePass.scale-baseline)<0.001 && preview.layoutScale()==1,"card native baked baseline and independent layout");
        return new LinkedHashMap<>(Map.of("role",role,"source",nativePass.source,"model",appearance.model().name(),"mask",appearance.outerLayerVisibility().toString(),"nativeScale",nativePass.scale,"requestScale",request.scale(),"layoutScale",preview.layoutScale(),"materials",nativePass.materialPasses,"drawTerminals",nativePass.primaryDraws,"completions",nativePass.completions));
    }
    public void close() {
        for(var value:created)value.close();created.clear();skins.close();capes.close();textures.close();
    }
}
