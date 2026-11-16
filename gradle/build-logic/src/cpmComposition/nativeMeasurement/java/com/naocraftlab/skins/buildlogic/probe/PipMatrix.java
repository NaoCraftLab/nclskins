package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.runtime.*;
import com.naocraftlab.skins.compat.cpm.*;
import com.tom.cpm.shared.definition.*;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.renderer.state.gui.*;
import net.minecraft.client.renderer.state.gui.pip.*;
import net.minecraft.client.renderer.feature.*;
import static com.naocraftlab.skins.buildlogic.probe.PipMaterialProbe.*;

public final class PipMatrix {
    record Captured(Object owner,PipCurrentHost.Loader loader,GuiEntityRenderState pip,EffectiveSkinSource source,OwnedCpmDefinition definition,ScreenOwnedRenderTarget target,UUID profile,List<PreviewRenderer.BodyStatus> notifications){}
    static Captured capture(String name,PreviewRenderer.PreviewAppearance selected,com.naocraftlab.skins.probe.EarsJoinBridge.Processed processed,ModelDefinitionLoader<UUID> definitions,boolean freshOwner)throws Exception{
        com.naocraftlab.skins.probe.PipNativeInputs.select(processed);PipCurrentHost.expectedMaterial=((net.minecraft.client.renderer.texture.AbstractTexture)processed.texture()).getTextureView();
        PipCurrentHost.appearance=selected;if(freshOwner)PipCurrentHost.initialize();var owner=PipCurrentHost.renderer;var type=owner.getClass();var notifications=new ArrayList<PreviewRenderer.BodyStatus>();type.getMethod("observeBody",Consumer.class).invoke(owner,(Consumer<PreviewRenderer.BodyStatus>)notifications::add);
        check((boolean)type.getMethod("prepareGalleryBody",String.class,boolean.class,BooleanSupplier.class).invoke(owner,name,true,(BooleanSupplier)processed::current),"actual host accepts current selected source "+name);
        var profile=(com.mojang.authlib.GameProfile)get(type,"previewProfile",owner);
        var source=CapeProjection.effectiveSkin(profile.id(),WORLD,selected.skin().location(),SkinModel.CLASSIC,true);call(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);
        var player=definitions.loadPlayer(profile.id(),ModelDefinitionLoader.PLAYER_UNIQUE);var live=player.getDefinitionFuture().get(5,java.util.concurrent.TimeUnit.SECONDS);live.startResolve();long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while(live.getResolveState()==ModelDefinition.ModelLoadingState.RESOLVING&&System.nanoTime()<until)Thread.sleep(1);player.getModelDefinition();check(live.doRender()&&((OwnedCpmDefinition)live).source()==source,"exact current host cached-player owns decoded definition");PipCurrentHost.player=player;
        PipCurrentHost.submitted.clear();type.getMethod("renderGallery",PipCurrentHost.Graphics.class,String.class,PreviewRenderer.PreviewRequest.class).invoke(owner,new PipCurrentHost.Graphics(),name,new PreviewRenderer.PreviewRequest(selected,0,0,160,180,0,0,name.endsWith("B")?0.85F:1,PreviewRenderer.PreviewIntent.EDITOR_DRAFT));
        check(PipCurrentHost.submitted.size()==1,"one actual compiled host captured native GUI state");var pip=PipCurrentHost.submitted.get(0);var context=PipCurrentHost.marker.getMethod("nclskins$previewContext").invoke(pip.renderState());var target=(ScreenOwnedRenderTarget)PipCurrentHost.contextType.getMethod("target").invoke(context);check(target!=null,"actual public Gallery host owns per-source PIP target");
        return new Captured(owner,PipCurrentHost.loader,pip,source,(OwnedCpmDefinition)live,target,profile.id(),notifications);
    }
    static Object publicRenderer(PipCurrentHost.Loader loader)throws Exception{return loader.loadClass(PipCurrentHost.GUI).getConstructor(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class).newInstance(minecraft.getEntityRenderDispatcher());}
    static PreviewModelFrames.Body body(Captured c)throws Exception{return(PreviewModelFrames.Body)c.owner().getClass().getMethod("body").invoke(c.owner());}
    static void close(Captured c)throws Exception{c.owner().getClass().getMethod("close").invoke(c.owner());}
    static void retire(Captured c,String action){
        try{
            if(action.equals("reload")){
                long[] generation={-1};var resources=new SkinCatalogSource(){public byte[] load(String a,String b,SkinModel model){throw new AssertionError("no resource I/O");}public long generation(){return generation[0];}};
                var state=SkinExtensionEnvironmentSource.State.INACTIVE;var environment=new com.naocraftlab.skins.compat.environment.SkinExtensionEnvironmentSupport(resources,state,state,state);environment.snapshot();generation[0]=-2;environment.snapshot();
            }else if(action.equals("retire"))CapeProjection.retireSkin(c.profile());
            if(action.equals("close"))close(c);else c.owner().getClass().getMethod("prepareBody",String.class,boolean.class).invoke(c.owner(),"successor-after-"+action,true);
            call(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);
            check(!c.source().active()&&!c.definition().doRender(),"actual "+action+" retires captured definition/source");
            check((boolean)get(OwnedCpmDefinition.class,"freed",c.definition()),"actual definition cleanup physically scheduled/freed during "+action);
        }catch(Exception e){throw new AssertionError(e);}
    }
    static List<BlitRenderState> nativePrepare(Captured c,Object publicRenderer,FeatureRenderDispatcher features,GuiRenderState gui)throws Exception{
        gui.addPicturesInPictureState(c.pip());var order=new ArrayList<PictureInPictureRenderState>();gui.forEachPictureInPicture(order::add);check(order.size()==1&&order.get(0)==c.pip(),"genuine dependency GUI collector retains actual captured payload by identity");
        for(var state:order)c.loader().loadClass(PipCurrentHost.GUI).getMethod("prepare",PictureInPictureRenderState.class,GuiRenderState.class,FeatureRenderDispatcher.class,int.class).invoke(publicRenderer,state,gui,features,1);
        var blits=new ArrayList<BlitRenderState>();gui.forEachElement(element->{if(element instanceof BlitRenderState b)blits.add(b);},GuiRenderState.TraverseRange.ALL);return blits;
    }
    static void consume(BlitRenderState blit){
        var view=blit.textureSetup().texure0();check(!view.isClosed()&&!view.texture().isClosed(),"actual deferred GUI material remains alive");
        var sink=new UpstreamABProbe.Sink();blit.buildVertices(sink);check(sink.vertices==4,"genuine native GUI blit emits its four vertices");
    }
    static void reset(){PipCurrentHost.ownMaterialAcquisitions=0;PipCurrentHost.meshVertices=0;PipCurrentHost.meshMin=Double.POSITIVE_INFINITY;PipCurrentHost.meshMax=Double.NEGATIVE_INFINITY;}
    static void positive(Captured c,List<BlitRenderState> blits,String label)throws Exception{
        check(blits.size()==1&&PipCurrentHost.meshVertices>0&&PipCurrentHost.meshMax>PipCurrentHost.meshMin&&PipCurrentHost.ownMaterialAcquisitions>0&&body(c).current().getAsBoolean(),"positive actual BODY/own material precedes every negative "+label);
        var output=blits.get(0).textureSetup().texure0();check(PipCpuBackend.drawRecords.stream().anyMatch(d->d.target()==output&&!d.sampled().isEmpty()),"native feature drain consumes actual selected BODY materials into exact owned target");
        Object held=get(ScreenOwnedRenderTarget.class,"resource",c.target());Object selected=get(held.getClass(),"value",held);check(selected.getClass()==c.loader().loadClass(PipCurrentHost.GUI),"actual public PIP prepare selects genuine SDK target renderer");
    }
    static void run(PreviewRenderer.PreviewAppearance selected,com.naocraftlab.skins.probe.EarsJoinBridge.Processed processed,ModelDefinitionLoader<UUID> definitions,FeatureRenderDispatcher features)throws Exception{
        PipCurrentHost.expectedMaterial=((net.minecraft.client.renderer.texture.AbstractTexture)processed.texture()).getTextureView();
        try(var profiles=CpmBehaviorProbe.nativeProfileScope();var changed=com.naocraftlab.skins.probe.PipNativeInputs.process(java.nio.file.Path.of(System.getProperty("nclskins.pip.changedCarrier")))){

            var changedAppearance=new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle(changed.id().toString(),64,64),SkinModel.CLASSIC,Optional.empty(),PreviewRenderer.CapeMode.OFF,true);
            check(!changed.id().equals(processed.id())&&!java.util.Arrays.equals(changed.pixels(),processed.pixels()),"frozen existing A/B carriers have genuinely different content and native registrations");
            for(String action:List.of("replace","close","reload","retire"))for(String phase:List.of("before-prepare","before-acquisition","after-acquisition","after-prepare")){
                String label=action+"/"+phase;var a=capture(label+"/A",selected,processed,definitions,true);Object publicPip=publicRenderer(a.loader());var consumer=a.loader().loadClass("com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame");
                reset();PipCpuBackend.View outputA;int aVertices;
                try(var frame=(AutoCloseable)consumer.getMethod("open", Object.class).invoke(null, consumer)){
                    var blits=nativePrepare(a,publicPip,features,new GuiRenderState());positive(a,blits,label);aVertices=PipCurrentHost.meshVertices;outputA=(PipCpuBackend.View)blits.get(0).textureSetup().texure0();consume(blits.get(0));consumer.getMethod("submitted").invoke(null);
                }
                vertices.endFrame();PipCpuBackend.signal();a.owner().getClass().getMethod("confirmGalleryBody",String.class).invoke(a.owner(),label+"/A");
                reset();int beforeDraws=PipCpuBackend.draws;int[] actions={0};Runnable retirement=()->{if(actions[0]++==0)retire(a,action);};
                if(phase.equals("before-prepare"))retirement.run();else if(phase.equals("before-acquisition"))PipCurrentHost.beforeAcquisition=retirement;else if(phase.equals("after-acquisition"))PipCurrentHost.afterAcquisition=retirement;
                List<BlitRenderState> held;
                try(var frame=(AutoCloseable)consumer.getMethod("open", Object.class).invoke(null, consumer)){
                    held=nativePrepare(a,publicPip,features,new GuiRenderState());if(phase.equals("after-prepare"))retirement.run();PipCurrentHost.beforeAcquisition=()->{};PipCurrentHost.afterAcquisition=()->{};
                    check(actions[0]>0,"tested lifecycle action actually executed "+label);
                    if(phase.equals("before-prepare"))check(held.isEmpty()&&PipCurrentHost.meshVertices==0&&PipCurrentHost.ownMaterialAcquisitions==0&&PipCpuBackend.draws==beforeDraws,"retired captured state refuses all native acquisition/drain/material");
                    else{check(held.size()==1&&PipCurrentHost.meshVertices==aVertices&&PipCurrentHost.ownMaterialAcquisitions>0,"actual deferred native records/material survive retirement "+label);check(held.get(0).textureSetup().texure0()==outputA&&!outputA.isClosed(),"captured A target remains own while consumer lease held");consume(held.get(0));}
                    consumer.getMethod("submitted").invoke(null);
                }finally{PipCurrentHost.beforeAcquisition=()->{};PipCurrentHost.afterAcquisition=()->{};}
                check(!body(a).current().getAsBoolean(),"retired A cannot publish a current BODY");if(!phase.equals("before-prepare"))check(!outputA.isClosed()&&outputA.closes==0,"logical retirement waits for genuine RenderSystem fence");vertices.endFrame();
                boolean newOwner=action.equals("close");var b=capture(label+"/B",changedAppearance,changed,definitions,newOwner);Object pipB=newOwner?publicRenderer(b.loader()):publicPip;var consumerB=b.loader().loadClass("com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame");reset();PipCpuBackend.View outputB;
                try(var frame=(AutoCloseable)consumerB.getMethod("open", Object.class).invoke(null, consumerB)){
                    var blits=nativePrepare(b,pipB,features,new GuiRenderState());positive(b,blits,label+"/B");check(PipCurrentHost.meshVertices!=aVertices,"changed authored B source emits distinguishable genuine geometry");outputB=(PipCpuBackend.View)blits.get(0).textureSetup().texure0();check(outputB!=outputA&&outputB.texture()!=outputA.texture(),"actual per-source A/B material resources are distinct");consume(blits.get(0));
                    int bNotifications=b.notifications().size(),bAcquisitions=PipCurrentHost.ownMaterialAcquisitions,bDraws=PipCpuBackend.draws,bVertices=PipCurrentHost.meshVertices;var stale=nativePrepare(a,publicPip,features,new GuiRenderState());check(stale.isEmpty()&&bNotifications==b.notifications().size()&&bAcquisitions==PipCurrentHost.ownMaterialAcquisitions&&bDraws==PipCpuBackend.draws&&bVertices==PipCurrentHost.meshVertices&&body(b).current().getAsBoolean(),"late retired A has no successor notification/material/geometry effect");consumerB.getMethod("submitted").invoke(null);
                }
                vertices.endFrame();PipCpuBackend.signal();check(outputA.isClosed()&&outputA.closes==1&&((PipCpuBackend.Texture)outputA.texture()).closes==1,"native retired A material/view cleanup exactly once after own fence");check(!outputB.isClosed()&&outputB.closes==0&&body(b).current().getAsBoolean(),"A fence cleanup leaves own current B untouched");
                close(b);PipCpuBackend.signal();check(outputB.isClosed()&&outputB.closes==1&&((PipCpuBackend.Texture)outputB.texture()).closes==1,"own B exact-once material cleanup");((AutoCloseable)publicPip).close();if(newOwner)((AutoCloseable)pipB).close();
                var row=Map.<String,Object>of("case",label,"positiveA",true,"positiveB",true,"actualMeshVerticesA",aVertices,"actualMeshVerticesB",PipCurrentHost.meshVertices,"ownMaterialAcquisitionsB",PipCurrentHost.ownMaterialAcquisitions,"exactOnceA",outputA.closes,"exactOnceB",outputB.closes,"successorUnaffected",true,"inputKind","frozen-different-content/new-source-owner");rows.add(row);System.out.println("PIP_MATERIAL_CASE "+new com.google.gson.Gson().toJson(row));
            }
        }
    }
}
