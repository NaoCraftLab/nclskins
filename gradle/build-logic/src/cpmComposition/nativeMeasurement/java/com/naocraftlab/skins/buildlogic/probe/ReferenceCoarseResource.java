package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.runtime.*;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import static com.naocraftlab.skins.buildlogic.probe.PipMaterialProbe.*;

final class ReferenceCoarseResource {
    static final List<Map<String,Object>> cases=new ArrayList<>();

    static List<BlitRenderState> prepare(GuiEntityRenderState state,Object pip) throws Exception {
        ReferenceBodyProbe.reset();var gui=new GuiRenderState();
        ReferenceBodyHost.loader.loadClass(ReferenceBodyHost.GUI).getMethod("prepare",PictureInPictureRenderState.class,GuiRenderState.class,FeatureRenderDispatcher.class,int.class).invoke(pip,state,gui,ReferenceBodyProbe.features,1);
        var blits=new ArrayList<BlitRenderState>();gui.forEachElement(e->{if(e instanceof BlitRenderState b)blits.add(b);},GuiRenderState.TraverseRange.ALL);
        return blits;
    }

    static void execute(GalleryNativePassFixture subject,Object host,Method sync,Method draw,Object pip,Class<?> consumer,
            Map<String,String> geometry,PreviewRenderer.ModelFitPreparation fit,PreviewModelFitStore.Key aKey,String skin,String order) throws Exception {
        ReferenceScreenDemand.retainedFrame(subject,host,sync,draw,pip,consumer,false,false,geometry);
        var collector=new GuiRenderState();
        for(var state:ReferenceBodyHost.submitted)collector.addPicturesInPictureState(state);
        var queued=new ArrayList<PictureInPictureRenderState>();collector.forEachPictureInPicture(queued::add);
        var state=(GuiEntityRenderState)queued.stream().filter(s->s.x0()>=0 && ((ReferenceBodyHost.RawAvatar)((GuiEntityRenderState)s).renderState()).appearance.skin().location().equals(skin)).findFirst().orElseThrow();
        ReferenceScreenDemand.retainedFrame(subject,host,sync,draw,pip,consumer,false,false,geometry);
        var acquiredState=ReferenceBodyHost.submitted.stream().filter(s->s.x0()>=0 && ((ReferenceBodyHost.RawAvatar)s.renderState()).appearance.skin().location().equals(skin)).findFirst().orElseThrow();
        check(state!=acquiredState && state.renderState()!=acquiredState.renderState(),"queued A and acquired A are distinct actual native payloads");
        var raw=(ReferenceBodyHost.RawAvatar)state.renderState();
        var client=ReferenceBodyHost.RawClient.current;
        var world=client.level;var player=client.player;
        var registration=NativePlayerSkinLifecycle.registration(skin);
        var slots=(Map<?,?>)get(ReferenceBodyHost.renderer.getClass(),"gallerySlots",ReferenceBodyHost.renderer);
        var owner=(EditorPreviewSession)get(fit.getClass(),"owner",fit);
        Object slot=null;
        for(var value:slots.values())if(get(value.getClass(),"session",value)==owner)slot=value;
        check(slot!=null,"confirmed A owns actual native slot");
        var target=(ScreenOwnedRenderTarget)get(slot.getClass(),"target",slot);
        var oldFrame=owner.frame();
        var frame=(AutoCloseable)consumer.getMethod("open", Object.class).invoke(null, consumer);
        PipCpuBackend.View output;
        try {
            com.naocraftlab.skins.probe.PipNativeInputs.select(ReferenceBodyHost.retainedInputs.get(skin));ReferenceBodyHost.player=raw.ownedPlayer;
            ReferenceBodyHost.expectedMaterial=ReferenceBodyHost.retainedInputs.get(skin).texture().getTextureView();
            var blits=prepare(acquiredState,pip);
            check(blits.size()==1 && ReferenceBodyProbe.verticesCount>0,"actual A acquired before coarse revoke");
            output=(PipCpuBackend.View)blits.get(0).textureSetup().texure0();
            int captures=FitCountingObservation.captures,loads=subject.numericLoads,creates=subject.numericCreates;
            subject.runtime.resourcesReloaded();
            check(client.level==world && client.player==player && NativePlayerSkinLifecycle.registration(skin).equals(registration),"coarse event preserves native world/player/registration identities");
            int draws=PipCpuBackend.draws;var rejected=prepare(state,pip);
            System.out.println("COARSE_RESOURCE_ATTEMPT order="+order+" draws="+(PipCpuBackend.draws-draws)+" vertices="+ReferenceBodyProbe.verticesCount+" materials="+ReferenceBodyHost.ownMaterialAcquisitions+" definitions="+ReferenceBodyProbe.exactDefinitionBindings);
            check(rejected.isEmpty() && draws==PipCpuBackend.draws && ReferenceBodyProbe.verticesCount==0 && ReferenceBodyHost.ownMaterialAcquisitions==0 && ReferenceBodyProbe.exactDefinitionBindings==0,"coarse resource revoke rejects queued A before material/geometry acquisition: "+order);
            check(!fit.current() && (boolean)get(ScreenOwnedRenderTarget.class,"closed",get(fit.getClass(),"target",fit)),"old native fit retired");
            check((boolean)get(ScreenOwnedRenderTarget.class,"closed",target) && slots.isEmpty() && !oldFrame.getAsBoolean(),"coarse revoke retires native sessions and logical target immediately");
            check(subject.displayed()==PreviewRenderer.DisplayKind.UNCONFIRMED,"old A has no common ACK");
            check(!output.isClosed() && output.closes==0,"acquired native A survives logical revoke until fence");
            PipMatrix.consume(blits.get(0));consumer.getMethod("submitted").invoke(null);
            check(captures==FitCountingObservation.captures && loads==subject.numericLoads && creates==subject.numericCreates,"resource event starts no new numeric demand");
        } finally {frame.close();vertices.endFrame();}
        check(!output.isClosed() && output.closes==0,"consumer close enqueues but does not signal fence");
        PipCpuBackend.signal();check(output.isClosed() && output.closes==1 && ((PipCpuBackend.Texture)output.texture()).closes==1,"coarse A physical cleanup exactly once after fence");
        var store=new com.naocraftlab.skins.runtime.infrastructure.PreviewModelFitStorageAdapter(new com.naocraftlab.skins.core.storage.NclSkinsStorage((Path)get(GalleryNativePassFixture.class,"directory",subject),new com.naocraftlab.skins.core.png.PngValidator(),java.time.Clock.systemUTC()));
        check(store.load(aKey).status()==PreviewModelFitStore.Status.PRESENT && subject.durableNumericFit().status()==PreviewModelFitStore.Status.PRESENT,"coarse revoke preserves lawful A/B numeric records");
        var operation=get(ClientRuntime.class,"numericFitOperation",subject.runtime);
        var demand=get(operation.getClass(),"demand",operation);
        int captures=FitCountingObservation.captures,loads=subject.numericLoads,creates=subject.numericCreates;
        int ambientVertices=0,ambientMaterials=0,ambientDefinitions=0,ambientFull=0,ambientFactorOne=0,ambientFrames=0;
        for(int i=0;i<110;i++) {
            if(i==100) {
                var view=subject.runtime.view(854,480,0,0);
                check(view.texts().stream().anyMatch(t->t.id().equals("gallery.layers"))
                        && view.widget("gallery.outer_layer.head").orElseThrow().activationEnabled(),"actual published ORDINARY controls follow fallback indicator");
                String previousSource=subject.runtime.previewBodySource(subject.runtime.galleryPreviewCandidate().orElseThrow());
                subject.runtime.dispatchWidget("gallery.outer_layer.head");
                check(!previousSource.equals(subject.runtime.previewBodySource(subject.runtime.galleryPreviewCandidate().orElseThrow())),"admitted mask input refines render key without new semantic demand");
            }
            ReferenceScreenDemand.retainedFrame(subject,host,sync,draw,pip,consumer,false,true,geometry);
            if(i<100)ambientFrames++;
            for(var emitted:ReferenceBodyHost.submitted) {
                var blits=ReferenceScreenDemand.retainedPrepare(emitted,pip,consumer,false);
                ambientVertices+=ReferenceBodyProbe.verticesCount;
                ambientMaterials+=ReferenceBodyHost.ownMaterialAcquisitions;
                ambientDefinitions+=ReferenceBodyProbe.exactDefinitionBindings;
                if(!blits.isEmpty() && ((PreviewRenderer<?>)ReferenceBodyHost.renderer).displayKind()==PreviewRenderer.DisplayKind.CPM) {
                    ambientFull++;
                    var request=ReferenceBodyHost.lastRequest;
                    if(Math.abs(emitted.scale()-0.97*request.height()/2.125*request.scale())<0.001)ambientFactorOne++;
                }
            }
            check(get(ClientRuntime.class,"numericFitOperation",subject.runtime)==operation
                    && get(operation.getClass(),"demand",operation).equals(demand),"ambient host keeps exact semantic demand owner");
            check(captures==FitCountingObservation.captures && loads==subject.numericLoads && creates==subject.numericCreates,"ambient frames do not retry numeric work");
            System.out.println("COARSE_AMBIENT order="+order+" frame="+i+" full="+ambientFull+" factorOne="+ambientFactorOne+" vertices="+ambientVertices+" materials="+ambientMaterials+" definitions="+ambientDefinitions+" native="+((PreviewRenderer<?>)ReferenceBodyHost.renderer).displayKind()+" common="+subject.displayed());
            check(ambientFull==0 && subject.displayed()!=PreviewRenderer.DisplayKind.CPM,"ambient same terminal demand cannot regain FULL CPM without own fit before reopen: "+order);
        }
        check(client.level==world && client.player==player && NativePlayerSkinLifecycle.registration(skin).equals(registration),"ambient interval preserves native context identities");
        int ambientLoads=subject.numericLoads-loads,ambientCaptures=FitCountingObservation.captures-captures,ambientCreates=subject.numericCreates-creates;
        var ambientNative=((PreviewRenderer<?>)ReferenceBodyHost.renderer).displayKind();var ambientCommon=subject.displayed();
        check(ambientNative==PreviewRenderer.DisplayKind.ORDINARY && ambientCommon==PreviewRenderer.DisplayKind.ORDINARY,"settled ambient fallback has own native completion and common ORDINARY indicator");
        subject.runtime.closeScreen();subject.runtime.reopen();subject.drainWorkers();subject.selectLast();
        ReferenceScreenDemand.retainedScales.clear();
        for(int i=0;i<80 && subject.displayed()!=PreviewRenderer.DisplayKind.CPM;i++)ReferenceScreenDemand.retainedFrame(subject,host,sync,draw,pip,consumer,true,true,geometry);
        check(subject.displayed()==PreviewRenderer.DisplayKind.CPM && subject.preview().presetId().equals(subject.runtime.snapshot().selectedPresetId()),"same runtime reopened fresh C owns native material and ACK");
        var row=new LinkedHashMap<String,Object>(Map.of("order",order,"staleVertices",0,"staleMaterials",0,"retiredFit",true,"retiredTarget",true,"physicalCloses",output.closes,"freshDisplay","CPM","durable","PRESENT"));
        row.put("ambientFrames",ambientFrames);row.put("ambientFull",ambientFull);row.put("ambientFactorOne",ambientFactorOne);
        row.put("ambientVertices",ambientVertices);row.put("ambientMaterials",ambientMaterials);row.put("ambientDefinitions",ambientDefinitions);
        row.put("ambientNumericLoads",ambientLoads);row.put("ambientCaptures",ambientCaptures);row.put("ambientCreates",ambientCreates);
        row.put("ambientNative",ambientNative.name());row.put("ambientCommon",ambientCommon.name());
        row.put("refinedFrames",10);row.put("ordinaryControls",true);row.put("sameDemandRenderRefinement",true);
        row.put("sameDemand",true);row.put("ambientBeforeReopen",true);
        cases.add(row);
        Files.writeString(Path.of(System.getProperty("probe.dir"),"coarse-resource.json"),new com.google.gson.Gson().toJson(cases));
        System.out.println("COARSE_RESOURCE_PASS "+order);
    }
}
