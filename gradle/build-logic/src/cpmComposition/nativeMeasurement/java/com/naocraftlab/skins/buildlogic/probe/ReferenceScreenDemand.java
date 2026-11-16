package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.runtime.*;
import com.naocraftlab.skins.probe.EarsJoinBridge;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import static com.naocraftlab.skins.buildlogic.probe.PipMaterialProbe.*;

final class ReferenceScreenDemand {
    static final List<Map<String,Object>> cases = new ArrayList<>();
    static final List<Map<String,Object>> semanticCases = new ArrayList<>();
    static final List<Map<String,Object>> earlyCases = new ArrayList<>();
    static final List<Map<String,Object>> lifecycleCases = new ArrayList<>();
    static final List<Map<String,Object>> fallbackCases = new ArrayList<>();
    static final List<Map<String,Object>> providerComparison = new ArrayList<>();

    @SuppressWarnings("unchecked")
    static java.util.function.BiFunction<Object,PreviewRenderer.PreviewAppearance,PreviewModelFrames.Body> absentCpmPreparation() throws Exception {
        var isolated = new ClassLoader(ReferenceScreenDemand.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.tom.")) throw new ClassNotFoundException(name);
                if (!name.equals(PreviewModelFrames.class.getName())) return super.loadClass(name,resolve);
                var type=findLoadedClass(name);
                if(type==null) try(var in=getParent().getResourceAsStream(name.replace('.','/')+".class")) {
                    var bytes=in.readAllBytes(); type=defineClass(name,bytes,0,bytes.length);
                } catch(java.io.IOException failure) { throw new ClassNotFoundException(name,failure); }
                if(resolve)resolveClass(type); return type;
            }
        };
        var type=isolated.loadClass(PreviewModelFrames.class.getName());
        check(type!=PreviewModelFrames.class,"missing CPM control owns genuinely uninstalled public preparation capability");
        return (java.util.function.BiFunction<Object,PreviewRenderer.PreviewAppearance,PreviewModelFrames.Body>)get(type,"preparation",null);
    }

    static void providersFallbackComparison(EarsJoinBridge.Processed input, GalleryNativePassFixture subject,
            Object host, Method sync, Method draw, Object renderer, PreviewRenderer.PreviewAppearance selected, String mode) throws Exception {
        var production=(PreviewRenderer<ReferenceBodyHost.Graphics>)renderer;
        var view=subject.runtime.view(854,480,0,0);
        sync.invoke(host,view); subject.drainWorkers(); sync.invoke(host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
        var preview=subject.runtime.view(854,480,0,0).previews().stream().filter(p->p.id().equals("editor.preview")).findFirst().orElseThrow();
        String sourceKey=subject.runtime.previewBodySource(preview);
        java.util.function.Supplier<PreviewRenderer.DisplayKind> capability=production::displayKind;
        host.getClass().getField("bodyCapability").set(host,capability);
        String initialSource=sourceKey;
        var observer=subject.runtime.observePreviewBody(preview,capability,()->production.confirmGalleryBody(initialSource),production::retireBody);
        host.getClass().getField("bodyObserver").set(host,observer);
        production.observeBody(observer);
        check(production.prepareGalleryImage(sourceKey,subject.runtime.previewBodyDemand(preview),subject.runtime.waitsForCpmBody(preview),
                subject.runtime.previewBodyAdmission(preview),subject.runtime.previewBodyLifetime(preview)),"baseline enters the same owning explicit host protocol");
        var imageSource=host.getClass().getDeclaredMethod("previewImageSource",ViewSpec.Preview.class);imageSource.setAccessible(true);
        production.prepareImage(imageSource.invoke(host,preview),0,true,false);
        var request=ViewHostPolicy.previewRequest(preview,Optional.of(selected),Optional.empty()).orElseThrow();
        production.render(new ReferenceBodyHost.Graphics(),request);
        var profile=(com.mojang.authlib.GameProfile)get(renderer.getClass(),"previewProfile",renderer);

        var source=CapeProjection.effectiveSkin(profile.id(),WORLD,input.id().toString(),SkinModel.CLASSIC,true);
        call(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);
        var player=ReferenceBodyProbe.definitions.loadPlayer(profile.id(),com.tom.cpm.shared.definition.ModelDefinitionLoader.PLAYER_UNIQUE);
        var definition=player.getDefinitionFuture().get(5,java.util.concurrent.TimeUnit.SECONDS);definition.startResolve();
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(definition.getResolveState()==com.tom.cpm.shared.definition.ModelDefinition.ModelLoadingState.RESOLVING && System.nanoTime()<until)Thread.sleep(1);
        check(player.getModelDefinition()!=null && definition.doRender() && source.active(),"same-owner baseline has genuine own resolved CPM definition");
        ReferenceBodyHost.player=player;
        var discovered=production.accessories(request);
        subject.runtime.observePreviewAccessories(preview,()->discovered);
        var settledPreview=subject.runtime.view(854,480,0,0).previews().stream().filter(p->p.id().equals("editor.preview")).findFirst().orElseThrow();
        if(!sourceKey.equals(subject.runtime.previewBodySource(settledPreview))) {
            preview=settledPreview;
            sourceKey=subject.runtime.previewBodySource(preview);
            String settledSource=sourceKey;
            observer=subject.runtime.observePreviewBody(preview,capability,()->production.confirmGalleryBody(settledSource),production::retireBody);
            host.getClass().getField("bodyObserver").set(host,observer);
            production.observeBody(observer);
            check(production.prepareGalleryImage(sourceKey,subject.runtime.previewBodyDemand(preview),subject.runtime.waitsForCpmBody(preview),
                    subject.runtime.previewBodyAdmission(preview),subject.runtime.previewBodyLifetime(preview)),"settled accessory composition owns baseline session");
            production.prepareImage(imageSource.invoke(host,preview),0,true,false);
            var projectAppearance=host.getClass().getDeclaredMethod("previewAppearance",ViewSpec.Preview.class,TextureRegistry.TextureHandle.class,SkinModel.class,Optional.class);
            projectAppearance.setAccessible(true);
            var ownAppearance=(PreviewRenderer.PreviewAppearance)projectAppearance.invoke(host,preview,selected.skin(),selected.model(),selected.cape());
            request=ViewHostPolicy.previewRequest(preview,Optional.of(ownAppearance),Optional.empty()).orElseThrow();
            production.render(new ReferenceBodyHost.Graphics(),request);
            profile=(com.mojang.authlib.GameProfile)get(renderer.getClass(),"previewProfile",renderer);
            source=CapeProjection.effectiveSkin(profile.id(),WORLD,input.id().toString(),SkinModel.CLASSIC,true);
            call(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);
            player=ReferenceBodyProbe.definitions.loadPlayer(profile.id(),com.tom.cpm.shared.definition.ModelDefinitionLoader.PLAYER_UNIQUE);
            definition=player.getDefinitionFuture().get(5,java.util.concurrent.TimeUnit.SECONDS);definition.startResolve();
            until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(definition.getResolveState()==com.tom.cpm.shared.definition.ModelDefinition.ModelLoadingState.RESOLVING && System.nanoTime()<until)Thread.sleep(1);
            check(player.getModelDefinition()!=null && definition.doRender() && source.active(),"settled baseline owns its own resolved CPM definition");
            ReferenceBodyHost.player=player;
        }
        var baselineSession=get(renderer.getClass(),"session",renderer);
        var factory=PreviewModelFrames.class.getDeclaredField("preparation");factory.setAccessible(true);
        @SuppressWarnings("unchecked") var original=(java.util.function.BiFunction<Object,PreviewRenderer.PreviewAppearance,PreviewModelFrames.Body>)factory.get(null);
        Object pip=ReferenceBodyHost.loader.loadClass(ReferenceBodyHost.GUI).getConstructor(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class).newInstance(minecraft.getEntityRenderDispatcher());
        var consumer=ReferenceBodyHost.loader.loadClass("com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame");
        int captures=FitCountingObservation.captures;
        String baselineGeometry=null; int baselineVertices=0; int calls=0;
        Map<String,Integer> baselineLayers=null;
        var liveGeometry=new ArrayList<String>();
        var liveContributions=new ArrayList<Map<String,Integer>>();
        int[] refusals={0}; var noEffects=subject.noEffectsCheckpoint();
        try {
            for(int phase=0;phase<3;phase++) {
                ReferenceBodyHost.submitted.clear();
                var expectedCapeMode=request.appearance().capeMode();
                if(phase==0) {
                    check(get(renderer.getClass(),"nativeFit",renderer)==null && subject.numericLoads==0,"baseline is actual same owner without normalization, not a new producer proof");
                    production.render(new ReferenceBodyHost.Graphics(),request);
                } else {
                    if(phase==1) {
                        if(mode.equals("declined")) {
                            var absent=absentCpmPreparation();
                            PreviewModelFrames.prepareWith((p,a)-> {refusals[0]++; return absent.apply(p,a);});
                        } else PreviewModelFrames.prepareWith((p,a)-> {
                            var own=original.apply(p,a);
                            check(own.status()==PreviewRenderer.BodyStatus.READY && own.current().getAsBoolean(),"typed refusal control starts from actual own ready preparation");
                            refusals[0]++;
                            return new PreviewModelFrames.Body(mode.equals("pending")?PreviewRenderer.BodyStatus.PENDING:PreviewRenderer.BodyStatus.FAILED,own.current());
                        });
                    }
                    var liveView=subject.runtime.view(854,480,0,0);
                    var livePreview=liveView.previews().stream().filter(p->p.id().equals("editor.preview")).findFirst().orElseThrow();
                    expectedCapeMode=livePreview.nativeAccessories()?livePreview.capeMode():PreviewRenderer.CapeMode.OFF;
                    draw.invoke(host,new ReferenceBodyHost.Graphics(),liveView); subject.drainWorkers();
                    var session=(EditorPreviewSession)get(renderer.getClass(),"session",renderer);
                    check(!session.waitsForBody(),"Providers retains its original unbounded nonwaiting policy");
                    long started=(long)get(EditorPreviewSession.class,"bodyStarted",session);
                    set(EditorPreviewSession.class,"nanoTime",session,(java.util.function.LongSupplier)()->started+java.time.Duration.ofSeconds(61).toNanos());
                }
                check(ReferenceBodyHost.submitted.size()==1,"baseline/refusal keeps original live renderer, no hidden numerical draw or baked switch: "+mode);
                var state=ReferenceBodyHost.submitted.get(0);ReferenceBodyProbe.reset();
                var ownState=(ReferenceBodyHost.RawAvatar)state.renderState();
                check(baselineSession==get(renderer.getClass(),"session",renderer) && profile.equals(get(renderer.getClass(),"previewProfile",renderer))
                        && source.active() && ownState.ownedPlayer==player && ownState.definition==definition,
                        "baseline/refusal native consumption retains exact session, profile, Player and definition phase="+phase);
                try(var frame=(AutoCloseable)consumer.getMethod("open", Object.class).invoke(null, consumer)) {
                    var gui=new GuiRenderState();
                    ReferenceBodyHost.loader.loadClass(ReferenceBodyHost.GUI).getMethod("prepare",PictureInPictureRenderState.class,GuiRenderState.class,FeatureRenderDispatcher.class,int.class).invoke(pip,state,gui,ReferenceBodyProbe.features,1);
                    var blits=new ArrayList<BlitRenderState>();gui.forEachElement(e->{if(e instanceof BlitRenderState b)blits.add(b);},GuiRenderState.TraverseRange.ALL);
                    check(blits.size()==1 && ReferenceBodyProbe.verticesCount>0 && ReferenceBodyProbe.exactDefinitionBindings>0,"original eleven-layer native draw retains own definition/material");
                    PipMatrix.consume(blits.get(0));consumer.getMethod("submitted").invoke(null);calls++;
                } finally {vertices.endFrame();PipCpuBackend.signal();}
                String geometry=ReferenceBodyProbe.digest();
                liveGeometry.add(geometry);
                liveContributions.add(new TreeMap<>(ReferenceVertices.counts));
                if(phase==0){baselineGeometry=geometry;baselineVertices=ReferenceBodyProbe.verticesCount;baselineLayers=new TreeMap<>(ReferenceVertices.counts);}
                else {
                    var actual=ReferenceBodyHost.lastRequest;
                    check(baselineVertices==ReferenceBodyProbe.verticesCount
                            && (phase==1 ? baselineLayers.equals(ReferenceVertices.counts)
                                : Objects.equals(baselineLayers.get("CPM_BODY"),ReferenceVertices.counts.get("CPM_BODY")))
                            && request.appearance().skin().equals(actual.appearance().skin()) && request.appearance().model()==actual.appearance().model()
                            && request.appearance().outerLayerVisibility().equals(actual.appearance().outerLayerVisibility())
                            && request.appearance().cape().equals(actual.appearance().cape()) && expectedCapeMode==actual.appearance().capeMode()
                            && request.yawDegrees()==actual.yawDegrees() && request.pitchDegrees()==actual.pitchDegrees()
                            && request.scale()==actual.scale() && request.height()==actual.height() && request.width()==actual.width()
                            && source.active() && profile.equals(get(renderer.getClass(),"previewProfile",renderer)),
                            "declined/pending/failed normalization preserves same-owner native layers, own selection and layout operands mode="+mode+" phase="+phase+" vertices="+baselineVertices+"/"+ReferenceBodyProbe.verticesCount+" layers="+baselineLayers.equals(ReferenceVertices.counts)+" skin="+request.appearance().skin().equals(actual.appearance().skin())+" model="+(request.appearance().model()==actual.appearance().model())+" mask="+request.appearance().outerLayerVisibility().equals(actual.appearance().outerLayerVisibility())+" cape="+request.appearance().cape().equals(actual.appearance().cape())+" mode="+(request.appearance().capeMode()==actual.appearance().capeMode())+" yaw="+(request.yawDegrees()==actual.yawDegrees())+" pitch="+(request.pitchDegrees()==actual.pitchDegrees())+" zoom="+(request.scale()==actual.scale())+" width="+(request.width()==actual.width())+" height="+(request.height()==actual.height())+" active="+source.active()+" profile="+profile.equals(get(renderer.getClass(),"previewProfile",renderer)));
                    check(production.displayKind()==PreviewRenderer.DisplayKind.ORDINARY,"refused/pending numerical fit never reports FULL from native readiness");
                    check(Math.abs(state.scale()-0.97*actual.height()/2.125*actual.scale())<0.001,"Providers fallback factor one, not cached FULL");
                }
            }
            check(refusals[0]>0 && subject.numericCreates==0 && FitCountingObservation.captures==captures,"public capability refusal reached; zero partial numerical capture/create mode="+mode+" refusals="+refusals[0]+" creates="+subject.numericCreates+" captures="+(FitCountingObservation.captures-captures));
            var operation=get(ClientRuntime.class,"numericFitOperation",subject.runtime);
            var result=(java.util.concurrent.CompletableFuture<?>)get(operation.getClass(),"result",operation);
            check(mode.equals("pending")?!result.isDone():result.isDone() && ((PreviewAssets.NumericFitResult)result.join()).value()==null,"pending stays pending; declined/failed never numeric FULL");
            noEffects.run();
            var row=new LinkedHashMap<String,Object>(Map.of("mode",mode,"sameOwnerBaseline",true,"nativePasses",calls,"vertices",baselineVertices,"liveGeometry",liveGeometry,"nativeContributions",baselineLayers,"firstCreates",subject.numericCreates,"captures",FitCountingObservation.captures-captures,"displayKind",production.displayKind().name(),"availabilityBoundary",mode.equals("declined")?"uninstalled-public-CPM-preparation":"source-current-typed-refusal"));
            row.put("liveContributions",liveContributions);row.put("refusalCalls",refusals[0]);row.put("past60Seconds",true);
            providerComparison.add(row);
        } finally {PreviewModelFrames.prepareWith(original);((AutoCloseable)pip).close();}
        for(String method:List.of("releasePreviewAccessories","releasePreviewBody")){var release=host.getClass().getDeclaredMethod(method);release.setAccessible(true);release.invoke(host);}
    }

    static void retained(Path a, Path b, Path c) throws Exception {
        try (var first=com.naocraftlab.skins.probe.PipNativeInputs.process(a);
             var second=com.naocraftlab.skins.probe.PipNativeInputs.process(b);
             var third=com.naocraftlab.skins.probe.PipNativeInputs.process(c)) {
            for(var input:List.of(first,second,third)) {
                ReferenceBodyHost.retainedInputs.put(input.id().toString(),input);

            }
            try {
                for(boolean stored:List.of(false,true)) retained(first,second,third,stored,null);
                for(String order:List.of("desired","retained")) retained(first,second,third,true,order);
            }
            finally { ReferenceBodyHost.retainedInputs.clear(); }
        }
    }

    static final List<Map<String,Object>> retainedCases=new ArrayList<>();
    static final Map<String,Float> retainedScales=new HashMap<>();

    static void retained(EarsJoinBridge.Processed a,EarsJoinBridge.Processed b,EarsJoinBridge.Processed c,boolean stored,String revoke) throws Exception {
        retainedScales.clear();
        int captures=FitCountingObservation.captures;
        var selected=new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle(a.id().toString(),64,64),
                SkinModel.CLASSIC,Optional.empty(),PreviewRenderer.CapeMode.OFF,OuterLayerVisibility.allVisible(),false,false,false);
        ReferenceBodyHost.appearance=selected; ReferenceBodyHost.player=null; ReferenceBodyHost.initialize();
        var renderer=ReferenceBodyHost.renderer;
        try(var subject=GalleryNativePassFixture.numeric(selected,a.content(),Files.createTempDirectory("retained-fit-"))) {
            subject.retainedSources(b.content(),c.content());
            var registry=new TextureRegistry() {
                public TextureHandle register(TextureKind kind,String hash,Path path) { throw new AssertionError("unexpected retained path texture"); }
                public TextureHandle register(TextureKind kind,String hash,byte[] bytes) {
                    var input=List.of(a,b,c).stream().filter(i->Arrays.equals(i.content(),bytes)).findFirst().orElseThrow();
                    return new TextureHandle(input.id().toString(),64,64);
                }
                public void release(TextureHandle handle) {}
                public void close() {}
            };
            Class<?> hostType=CpmPreviewScreenSlice.loadNative(ReferenceBodyHost.PREFIX+"NclSkinsScreen",renderer.getClass(),ReferenceBodyHost.Graphics.class,ReferenceBodyHost.Baked.class);
            Object host=hostType.getConstructor().newInstance();
            try(var skins=new PreviewAssetCache<Object>(registry,TextureRegistry.TextureKind.PLAYER_SKIN,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
                var capes=new PreviewAssetCache<Object>(registry,TextureRegistry.TextureKind.IMAGE,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
                for(var field:hostType.getDeclaredFields()) {
                    Object value=switch(field.getName()) {
                        case "runtime" -> subject.runtime;
                        case "editorRenderer" -> renderer;
                        case "skinTextures" -> skins;
                        case "capeTextures" -> capes;
                        case "textureRegistry" -> new CpmOwnedPreviewDispatchProbe.Registry();
                        default -> field.getType()==Map.class?new HashMap<>():null;
                    };
                    if(value!=null)field.set(host,value);
                }
                Method sync=hostType.getDeclaredMethod("syncPreviewAssets",ViewSpec.class);sync.setAccessible(true);
                Method draw=hostType.getDeclaredMethod("drawPreviews",ReferenceBodyHost.Graphics.class,ViewSpec.class);draw.setAccessible(true);
                Object pip=ReferenceBodyHost.loader.loadClass(ReferenceBodyHost.GUI).getConstructor(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class).newInstance(minecraft.getEntityRenderDispatcher());
                var consumer=ReferenceBodyHost.loader.loadClass("com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame");
                try {
                    var geometry=new HashMap<String,String>();
                    for(int i=0;i<80 && subject.displayed()!=PreviewRenderer.DisplayKind.CPM;i++) retainedFrame(subject,host,sync,draw,pip,consumer,true,true,geometry);
                    check(subject.displayed()==PreviewRenderer.DisplayKind.CPM && subject.numericCreates==1,"retained A actual own material and common ACK");
                    var shown=subject.preview();
                    var fitA=subject.durableNumericFit().value();
                    var nativeFitA=(PreviewRenderer.ModelFitPreparation)get(renderer.getClass(),"nativeFit",renderer);
                    var aKey=subject.numericKey;
                    if("desired".equals(revoke)) {
                        ReferenceCoarseResource.execute(subject,host,sync,draw,pip,consumer,geometry,nativeFitA,aKey,a.id().toString(),revoke);
                        return;
                    }
                    float nativeScaleA=retainedScales.get(a.id().toString());
                    var before=subject.runtime.view(854,480,0,0);
                    var publications=new ArrayList<ViewSpec>();
                    var subscription=subject.runtime.subscribe(ignored->publications.add(subject.runtime.view(854,480,0,0)));
                    publications.clear();
                    subject.selectC();
                    check(!subject.runtime.snapshot().selectedPresetId().equals(shown.presetId()),"actual Gallery selection chooses distinct B");
                    java.util.concurrent.CompletableFuture<?> pending=null;
                    for(int i=0;i<80;i++) {
                        retainedFrame(subject,host,sync,draw,pip,consumer,true,false,geometry);
                        var fit=get(renderer.getClass(),"nativeFit",renderer);
                        if(fit!=null) {
                            var result=(java.util.concurrent.CompletableFuture<?>)get(fit.getClass(),"result",fit);
                            if(result.isDone() && result.getNow(null)!=null) { pending=result; break; }
                        }
                        subject.drainWorkers();
                    }
                    check(pending!=null && subject.numericCreates==1,"genuine B fit completion held before queued real first-create");
                    var fitB=((PreviewRenderer.ModelFitMeasurement)pending.join()).fit();
                    check(fitA.fit().normalization()!=fitB.normalization(),"actual measured A/B factors discriminate foreign-fit use");
                    var bKey=subject.numericKey;
                    var bOperation=get(ClientRuntime.class,"numericFitOperation",subject.runtime);
                    var bResult=(java.util.concurrent.CompletableFuture<?>)get(bOperation.getClass(),"result",bOperation);
                    check(!bResult.isDone(),"numeric completion alone has no new display authority");
                    retainedComposition(before,subject.runtime.view(854,480,0,0),shown);
                    for(var publication:publications)retainedComposition(before,publication,shown);
                    publications.clear();
                    var noEffects=subject.noEffectsCheckpoint();
                    float old=subject.preview().scale();
                    subject.runtime.pointerScrolled(shown.anchorBounds().x()+shown.anchorBounds().width()/2.0,shown.anchorBounds().y()+shown.anchorBounds().height()/2.0,0,1);
                    float zoom=subject.preview().scale();
                    check(zoom!=old,"actual Gallery interaction owner changes retained zoom");
                    retainedFrame(subject,host,sync,draw,pip,consumer,true,false,geometry);
                    check(subject.preview().scale()==zoom && subject.preview().bodySource().equals(shown.bodySource()),"pending B retains whole A at changed user zoom");
                    check(nativeFitA.current(),"only completed displayed A retains native fit lifetime while candidate B is pending");
                    check(Math.abs(retainedScales.get(a.id().toString())-nativeScaleA*zoom/old)<0.001,"actual retained native A uses own baseline times changed user zoom, never B fit before="+nativeScaleA+" after="+retainedScales.get(a.id().toString())+" zoom="+old+"->"+zoom+" fitA="+fitA+" fitB="+fitB);
                    noEffects.run();
                    List<net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState> queued=List.of();
                    if("retained".equals(revoke)) {
                        subject.drainWorkers();
                        check(subject.durableNumericFit().status()==PreviewModelFitStore.Status.PRESENT,"lawful B record before coarse revoke");
                        subscription.close();
                        ReferenceCoarseResource.execute(subject,host,sync,draw,pip,consumer,geometry,nativeFitA,aKey,a.id().toString(),revoke);
                        return;
                    }
                    if(stored) {
                        subject.drainWorkers();
                        check(((PreviewAssets.NumericFitResult)bResult.join()).status()==PreviewAssets.NumericFitStatus.STORED,"B legitimately committed before retirement");
                        retainedFrame(subject,host,sync,draw,pip,consumer,false,false,geometry);
                        queued=ReferenceBodyHost.submitted.stream().filter(s->((ReferenceBodyHost.RawAvatar)s.renderState()).appearance.skin().location().equals(b.id().toString())).toList();
                        check(!queued.isEmpty(),"own B actual deferred frame queued after lawful durable creation");
                    }
                    subject.selectLast();
                    subject.drainWorkers();
                    if(!stored) check(subject.numericCreates==1 && ((PreviewAssets.NumericFitResult)bResult.join()).status()==PreviewAssets.NumericFitStatus.RETIRED,
                            "stale B actual queued first-create rejected after C selection");
                    var durable=new com.naocraftlab.skins.runtime.infrastructure.PreviewModelFitStorageAdapter(new com.naocraftlab.skins.core.storage.NclSkinsStorage(
                            (Path)get(GalleryNativePassFixture.class,"directory",subject),new com.naocraftlab.skins.core.png.PngValidator(),java.time.Clock.systemUTC())).load(bKey);
                    check(durable.status()==(stored?PreviewModelFitStore.Status.PRESENT:PreviewModelFitStore.Status.ABSENT),"fresh reader distinguishes lawful committed B from late first-create");
                    for(var stale:queued) {
                        int draws=PipCpuBackend.draws;
                        var blits=retainedPrepare(stale,pip,consumer,false);
                        check(blits.isEmpty() && PipCpuBackend.draws==draws && ReferenceBodyProbe.verticesCount==0
                                && ReferenceBodyHost.ownMaterialAcquisitions==0 && ReferenceBodyProbe.exactDefinitionBindings==0
                                && subject.preview().bodySource().equals(shown.bodySource()),"retired queued B cannot acquire material or ACK");
                    }
                    for(int i=0;i<80 && !subject.preview().presetId().equals(subject.runtime.snapshot().selectedPresetId());i++) retainedFrame(subject,host,sync,draw,pip,consumer,true,true,geometry);
                    check(subject.displayed()==PreviewRenderer.DisplayKind.CPM && subject.preview().presetId().equals(subject.runtime.snapshot().selectedPresetId())
                            && subject.preview().scale()==zoom,"whole own C promotion and common ACK preserve user zoom");
                    check(!nativeFitA.current(),"C common ACK retires old A fit rather than leaking displayed authority");
                    check(geometry.containsKey(a.id().toString()) && geometry.containsKey(c.id().toString())
                            && !geometry.get(a.id().toString()).equals(geometry.get(c.id().toString())),"A and C actual ordered native positions distinguish source geometry");
                    var finalPreview=subject.preview();
                    subscription.close();
                    for(var publication:publications) {
                        var body=publication.previews().stream().filter(p->p.id().equals("gallery.preview")).findFirst().orElseThrow();
                        check(body.bodySource().equals(shown.bodySource()) || body.bodySource().equals(finalPreview.bodySource()),"every subscriber projection is whole retained A or own accepted C, never B");
                    }
                    int classifiers=FitCountingObservation.classifiers,copies=FitCountingObservation.copies,loads=subject.numericLoads,creates=subject.numericCreates;
                    for(int i=0;i<100;i++)retainedFrame(subject,host,sync,draw,pip,consumer,true,true,geometry);
                    check(FitCountingObservation.captures-captures==6 && classifiers==FitCountingObservation.classifiers && copies==FitCountingObservation.copies
                            && loads==subject.numericLoads && creates==subject.numericCreates,"three exact native measurement pairs and 100 settled frames without new fit work");
                    retainedCases.add(Map.of("storedBeforeRetirement",stored,"aGeometry",geometry.get(a.id().toString()),"cGeometry",geometry.get(c.id().toString()),
                            "zoom",zoom,"staleFrames",queued.size(),"firstCreates",subject.numericCreates,"displayKind","CPM",
                            "aNormalization",fitA.fit().normalization(),"bNormalization",fitB.normalization()));
                    System.out.println("RETAINED_FIT_PASS stored="+stored+" zoom="+zoom+" staleFrames="+queued.size());
                } finally {
                    ((AutoCloseable)pip).close();
                    for(String method:List.of("releasePreviewAccessories","releasePreviewBody")) {var release=hostType.getDeclaredMethod(method);release.setAccessible(true);release.invoke(host);}
                }
            } finally {((AutoCloseable)renderer).close();}
        }
        Files.writeString(Path.of(System.getProperty("probe.dir"),"retained-fit.json"),new com.google.gson.Gson().toJson(retainedCases));
    }

    static void retainedComposition(ViewSpec before,ViewSpec after,ViewSpec.Preview shown) {
        var preview=after.previews().stream().filter(p->p.id().equals("gallery.preview")).findFirst().orElseThrow();
        check(preview.equals(shown),"subscriber whole retained A body/source/transform/indicator");
        for(var widget:before.widgets()) if(widget.id().startsWith("gallery.inspect.") || widget.id().startsWith("gallery.outer_layer.") || widget.id().equals("gallery.preview.compatibility")) {
            var next=after.widget(widget.id()).orElseThrow();
            var expected=widget.id().startsWith("gallery.inspect.") || widget.id().startsWith("gallery.outer_layer.") ? widget.withActivation(false,null) : widget;
            check(next.equals(expected),"retained controls preserve exact content/layout/style/indicator without B admission");
        }
        check(before.texts().stream().filter(t->t.id().equals("gallery.layers") || t.id().equals("gallery.capes")).toList()
                .equals(after.texts().stream().filter(t->t.id().equals("gallery.layers") || t.id().equals("gallery.capes")).toList()),"whole retained section headers remain unchanged");
        check(before.scrollSurface("gallery.stage").equals(after.scrollSurface("gallery.stage")),"whole retained lower-column layout and fractional scroll remain unchanged");
        check(after.widget("gallery.providers").orElseThrow().enabled() && after.widget("gallery.search").orElseThrow().enabled(),"independent Gallery UI remains available");
    }

    static void retainedFrame(GalleryNativePassFixture subject,Object host,Method sync,Method draw,Object pip,Class<?> consumer,
            boolean consume,boolean workers,Map<String,String> geometry) throws Exception {
        sync.invoke(host,subject.runtime.view(854,480,0,0));
        if(workers) subject.drainWorkers();
        ReferenceBodyHost.submitted.clear();ReferenceBodyHost.strata.clear();
        draw.invoke(host,new ReferenceBodyHost.Graphics(),subject.runtime.view(854,480,0,0));
        call(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);
        var profile=(com.mojang.authlib.GameProfile)get(ReferenceBodyHost.renderer.getClass(),"previewProfile",ReferenceBodyHost.renderer);
        var player=ReferenceBodyProbe.definitions.loadPlayer(profile.id(),com.tom.cpm.shared.definition.ModelDefinitionLoader.PLAYER_UNIQUE);
        var future=player.getDefinitionFuture();
        if(future!=null) {
            var definition=future.get(5,java.util.concurrent.TimeUnit.SECONDS);
            definition.startResolve();
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(definition.getResolveState()==com.tom.cpm.shared.definition.ModelDefinition.ModelLoadingState.RESOLVING && System.nanoTime()<until)Thread.sleep(1);
            player.getModelDefinition();
        }
        if(consume) {
            boolean hadDisplay=!retainedScales.isEmpty();
            var gui=new GuiRenderState();
            gui.addGuiElement((net.minecraft.client.renderer.state.gui.GuiElementRenderState)call(CpmExtractionBodyProbe.class,"sdkArea",new Class<?>[]{ClassLoader.class,Class.class,Object.class},null,
                    GuiRenderState.class.getClassLoader(),net.minecraft.client.renderer.state.gui.GuiElementRenderState.class,new net.minecraft.client.gui.navigation.ScreenRectangle(100,100,20,20)));
            int stratum=0;
            for(var state:ReferenceBodyHost.submitted) {
                int next=ReferenceBodyHost.strata.get(state);
                while(stratum<next) {gui.nextStratum();stratum++;}
                gui.addPicturesInPictureState(state);
            }
            var ordered=new ArrayList<PictureInPictureRenderState>();gui.forEachPictureInPicture(ordered::add);
            int visible=0;
            for(var state:ordered) {
                var blits=retainedPrepare((net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState)state,pip,consumer,true);
                if(!blits.isEmpty()) geometry.put(((ReferenceBodyHost.RawAvatar)((net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState)state).renderState()).appearance.skin().location(),ReferenceBodyProbe.digest());
                if(state.x0()>=0 && state.x0()<854) {
                    check(!blits.isEmpty(),"each visible retained/current native frame has own nonempty material, not offscreen candidate success");visible++;
                    var emitted=(net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState)state;
                    retainedScales.put(((ReferenceBodyHost.RawAvatar)emitted.renderState()).appearance.skin().location(),emitted.scale());
                }
            }
            if(hadDisplay) check(visible==1,"one complete visible native composition through genuine dependency collector");
        }
        if(workers)subject.drainWorkers();
    }

    static List<BlitRenderState> retainedPrepare(net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState state,Object pip,Class<?> consumer,boolean positive) throws Exception {
        var raw=(ReferenceBodyHost.RawAvatar)state.renderState();
        var input=ReferenceBodyHost.retainedInputs.get(raw.appearance.skin().location());
        com.naocraftlab.skins.probe.PipNativeInputs.select(input); ReferenceBodyHost.player=raw.ownedPlayer;
        ReferenceBodyHost.expectedMaterial=input.texture().getTextureView();
        try(var frame=(AutoCloseable)consumer.getMethod("open", Object.class).invoke(null, consumer)) {
            var gui=new GuiRenderState();ReferenceBodyProbe.reset();
            ReferenceBodyHost.loader.loadClass(ReferenceBodyHost.GUI).getMethod("prepare",PictureInPictureRenderState.class,GuiRenderState.class,FeatureRenderDispatcher.class,int.class).invoke(pip,state,gui,ReferenceBodyProbe.features,1);
            var blits=new ArrayList<BlitRenderState>();gui.forEachElement(e->{if(e instanceof BlitRenderState b)blits.add(b);},GuiRenderState.TraverseRange.ALL);
            if(!blits.isEmpty()) {
                check(ReferenceBodyProbe.verticesCount>0 && (ReferenceBodyHost.ownMaterialAcquisitions>0 || ReferenceBodyProbe.exactDefinitionBindings>0),"own native source material and genuine geometry");
                for(var blit:blits)PipMatrix.consume(blit);
                consumer.getMethod("submitted").invoke(null);
            }
            return blits;
        } finally {vertices.endFrame();PipCpuBackend.signal();}
    }

    static void execute(EarsJoinBridge.Processed input) throws Exception {
        for (String surface : List.of("gallery", "editor", "providers")) {
            run(input, surface, "worldless-draw");
            run(input, surface, "deadline-queued-create");
            if (!surface.equals("providers")) run(input, surface, "deadline-before-publication");
            run(input, surface, "early-worldless");
            run(input, surface, "worldless");
            run(input, surface, "missing-required");
            run(input, surface, "positive");
        }
        for (String mode : List.of("worldless-stale", "worldless-failed", "worldless-pending")) run(input, "providers", mode);

        Files.writeString(Path.of(System.getProperty("probe.dir"),"fallback-policy.json"),new com.google.gson.Gson().toJson(fallbackCases));
        Files.writeString(Path.of(System.getProperty("probe.dir"),"screen-demand.json"),new com.google.gson.Gson().toJson(cases));
        Files.writeString(Path.of(System.getProperty("probe.dir"),"semantic-demand.json"),new com.google.gson.Gson().toJson(semanticCases));
        Files.writeString(Path.of(System.getProperty("probe.dir"),"early-opening.json"),new com.google.gson.Gson().toJson(earlyCases));
        Files.writeString(Path.of(System.getProperty("probe.dir"),"lifecycle-first-frame.json"),new com.google.gson.Gson().toJson(lifecycleCases));
    }

    static void bakedSurfaces(EarsJoinBridge.Processed input) throws Exception {
        var root = Files.createTempDirectory("baked-surface-consumers-");
        var appearance = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle(input.id().toString(),64,64),
                SkinModel.CLASSIC,Optional.empty(),PreviewRenderer.CapeMode.OFF,OuterLayerVisibility.allVisible());
        try (var subject = GalleryNativePassFixture.numeric(appearance,input.content(),root)) {
            var measured = ReferenceBodyProbe.lastFit;
            check(measured.fit().normalization()<0.5,"real measured FULL record discriminates baked baseline");
            var key = new PreviewModelFitStore.Key(subject.sessionAccount,ReferenceBodyProbe.contentKey(input).split("/")[0],com.naocraftlab.skins.core.model.SkinVariant.CLASSIC);
            var store = new com.naocraftlab.skins.runtime.infrastructure.PreviewModelFitStorageAdapter(
                    new com.naocraftlab.skins.core.storage.NclSkinsStorage(root,new com.naocraftlab.skins.core.png.PngValidator(),java.time.Clock.systemUTC()));
            var value = new PreviewModelFitStore.Value(measured.referenceHeight(),measured.referenceCenterX(),measured.referenceCenterY(),measured.referenceCenterZ(),measured.fit());
            check(store.create(key,value,()->true).status()==PreviewModelFitStore.Status.PRESENT && store.load(key).value().equals(value),"actual numeric producer and fresh reader");
            int captures=FitCountingObservation.captures, classifiers=FitCountingObservation.classifiers, copies=FitCountingObservation.copies;
            var noEffects=subject.noEffectsCheckpoint();
            var ledger=new ArrayList<Map<String,Object>>();
            try(var menu=subject.coldMenu()) {
                for(boolean pause:List.of(false,true))try(var baked=new CpmBakedDisplayProbe.SelectedFallback()) {
                    check(menu.snapshot().lifecycle()==ClientSnapshot.Lifecycle.NEW,"cold menu without Gallery initialization");
                    baked.menu(menu,pause);baked.consume();
                    check(baked.primaryDraws==1 && baked.materialPasses==1 && baked.source.equals(input.id().toString()),"actual native menu own-source baked terminal");
                    check(Math.abs(baked.scale-0.97*110/2.125*0.92)<0.001,"matching FULL never shrinks native menu");
                    ledger.add(Map.of("role",pause?"pause":"title","scale",baked.scale,"fullFactor",value.fit().normalization(),"drawTerminals",baked.primaryDraws,"materials",baked.materialPasses,"coldRuntime",true));
                }
            }
            for(var card:com.naocraftlab.skins.runtime.BakedSurfacePresenterFixture.cards(subject.runtime)) {
                com.naocraftlab.skins.runtime.BakedSurfacePresenterFixture.verify(card);
                var preview=card.preview();
                var request=ViewHostPolicy.previewRequest(preview,Optional.of(appearance),Optional.of(appearance)).orElseThrow();
                boolean[] current={true};
                var completion=new PreviewModelFrames.Completion() {
                    public boolean current(){return current[0];}
                    public void accept(PreviewModelFrames.Body body){check(body.status()==PreviewRenderer.BodyStatus.ORDINARY,"actual baked completion only");}
                };
                try(var baked=new CpmBakedDisplayProbe.SelectedFallback()) {
                    baked.render(request,completion);check(baked.queued()==1 && baked.completions==0,"enqueue is not display");baked.consume();
                    check(baked.primaryDraws==1 && baked.materialPasses==1 && baked.completions==1 && baked.source.equals(input.id().toString()),"actual selected baked own-source pass "+card.role());
                    double baseline=0.97*request.height()/2.125*request.scale();
                    check(Math.abs(baked.scale-baseline)<0.001,"matching FULL record cannot shrink baked operand "+card.role());
                    check(Math.abs(baked.scale-baseline*value.fit().normalization())>1,"full-record wrong-scale sensitivity "+card.role());
                    ledger.add(Map.of("role",card.role(),"preview",preview.id(),"scale",baked.scale,"requestScale",request.scale(),"layoutScale",preview.layoutScale(),"fullFactor",value.fit().normalization(),"drawTerminals",baked.primaryDraws,"materials",baked.materialPasses,"completions",baked.completions));
                }
                try(var stale=new CpmBakedDisplayProbe.SelectedFallback()) {
                    stale.render(request,completion);current[0]=false;stale.consume();
                    check(stale.primaryDraws==0 && stale.materialPasses==0 && stale.completions==0,"queued stale source cannot draw or acknowledge "+card.role());
                }
            }
            check(ledger.size()==8 && ledger.stream().map(row->row.get("role")).distinct().count()==8,"literal baked owner roster");
            check(subject.numericLoads==0 && subject.numericCreates==0 && captures==FitCountingObservation.captures && classifiers==FitCountingObservation.classifiers && copies==FitCountingObservation.copies,"cards never demand FULL measurement or numeric lookup");
            check(store.load(key).value().equals(value),"baked cards preserve durable winner");noEffects.run();
            Files.writeString(Path.of(System.getProperty("probe.dir"),"baked-surface-consumers.json"),new com.google.gson.Gson().toJson(ledger));
            System.out.println("BAKED_SURFACE_CONSUMERS_PASS cards="+ledger.size()+" numericLoads=0 captures=0 creates=0");
        }
    }

    static void providers(EarsJoinBridge.Processed input) throws Exception {
        bakedSurfaces(input);
        BakedCardSourceProbe.execute(input);
        for(String mode:List.of("declined","pending","failed"))run(input,"providers","comparison-"+mode);
        Files.writeString(Path.of(System.getProperty("probe.dir"),"providers-fallback-comparison.json"),new com.google.gson.Gson().toJson(providerComparison));
    }

    static void run(EarsJoinBridge.Processed input, String surface, String mode) throws Exception {
        var selected = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle(input.id().toString(),64,64),
                SkinModel.CLASSIC, Optional.empty(), PreviewRenderer.CapeMode.OFF, OuterLayerVisibility.allVisible(), false, false, false);
        com.naocraftlab.skins.probe.PipNativeInputs.select(input);
        ReferenceBodyHost.appearance = selected;
        ReferenceBodyHost.expectedMaterial = input.texture().getTextureView();
        ReferenceBodyHost.player = null;
        ReferenceBodyHost.initialize();
        int missingChecks=ReferenceBodyHost.missingRequiredChecks;
        ReferenceBodyHost.loader.withoutEars=mode.equals("missing-required");
        Object renderer = ReferenceBodyHost.renderer;
        Class<?> rendererType = renderer.getClass();
        var root = Files.createTempDirectory("screen-demand-" + surface + "-");
        try (var subject = GalleryNativePassFixture.numeric(selected,input.content(),root,mode.equals("early-worldless"))) {
            if (surface.equals("editor")) {
                var id = subject.runtime.snapshot().selectedPresetId().orElseThrow();
                subject.runtime.dispatchWidget("gallery.preset." + id + ".more");
                subject.runtime.dispatchWidget("gallery.preset." + id + ".edit");
            } else if (surface.equals("providers")) subject.runtime.dispatchWidget("gallery.providers");
            var initialView = subject.runtime.view(854,480,0,0);
            String previewId = surface.equals("gallery") ? "gallery.preview" : "editor.preview";
            var initialPreview = initialView.previews().stream().filter(p -> p.id().equals(previewId)).findFirst().orElseThrow();
            check(initialPreview.scale()==1F,"new actual "+surface+" session begins at logical unit zoom");
            if(mode.equals("positive")) {
                var anchor=initialPreview.anchorBounds();
                subject.runtime.pointerScrolled(anchor.x()+anchor.width()/2.0,anchor.y()+anchor.height()/2.0,0,1);
                var zoomed=subject.runtime.view(854,480,0,0).previews().stream().filter(p->p.id().equals(previewId)).findFirst().orElseThrow();
                check(Math.abs(zoomed.scale()-1.08F)<0.000001,"actual input changes logical zoom before readiness: "+surface);
            }
            var view = subject.runtime.view(854,480,0,0);
            String screenId = view.screenId();
            String id = surface.equals("gallery") ? "gallery.preview" : "editor.preview";
            var preview = view.previews().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
            check(ViewHostPolicy.screenOwnedBody(view.screenId(),preview), "actual surface owns its BODY: " + surface);
            check(subject.runtime.waitsForCpmBody(preview) == !surface.equals("providers"), "actual surface-specific CPM wait: " + surface);
            var registry = new TextureRegistry() {
                public TextureHandle register(TextureKind kind,String hash,Path path) { throw new AssertionError("unexpected path texture"); }
                public TextureHandle register(TextureKind kind,String hash,byte[] bytes) {
                    check(kind == TextureKind.PLAYER_SKIN && Arrays.equals(bytes,input.content()), "screen registers exact current owned carrier");
                    return selected.skin();
                }
                public void release(TextureHandle handle) {}
                public void close() {}
            };
            Class<?> hostType = CpmPreviewScreenSlice.loadNative(ReferenceBodyHost.PREFIX + "NclSkinsScreen",rendererType,
                    ReferenceBodyHost.Graphics.class,ReferenceBodyHost.Baked.class);
            Object host = hostType.getConstructor().newInstance();
            try (var skins = new PreviewAssetCache<Object>(registry,TextureRegistry.TextureKind.PLAYER_SKIN,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
                 var capes = new PreviewAssetCache<Object>(registry,TextureRegistry.TextureKind.IMAGE,com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
                for (var field : hostType.getDeclaredFields()) {
                    Object value = switch(field.getName()) {
                        case "runtime" -> subject.runtime;
                        case "editorRenderer" -> renderer;
                        case "skinTextures" -> skins;
                        case "capeTextures" -> capes;
                        case "textureRegistry" -> new CpmOwnedPreviewDispatchProbe.Registry();
                        default -> field.getType() == Map.class ? new HashMap<>() : null;
                    };
                    if (value != null) field.set(host,value);
                }
                Method sync = hostType.getDeclaredMethod("syncPreviewAssets",ViewSpec.class); sync.setAccessible(true);
                Method draw = hostType.getDeclaredMethod("drawPreviews",ReferenceBodyHost.Graphics.class,ViewSpec.class); draw.setAccessible(true);
                String skippedSource = null;
                if (mode.equals("early-worldless")) {
                    var client = ReferenceBodyHost.RawClient.current;
                    var level = client.level;
                    int captures = FitCountingObservation.captures;
                    boolean openingSkipped;
                    try {
                        client.level = null;
                        sync.invoke(host,view);
                        ReferenceBodyHost.submitted.clear();
                        check(((Deque<?>)get(GalleryNativePassFixture.class,"previewWorkers",subject)).size()>0
                                && ((Map<?,?>)hostType.getField("previewSkinKeys").get(host)).values().stream().noneMatch(key->skins.handle(key).isPresent()),
                                "actual texture worker/publication held with no loaded handle: "+surface);
                        draw.invoke(host,new ReferenceBodyHost.Graphics(),view);
                        var opening=get(ClientRuntime.class,"numericFitOperation",subject.runtime);
                        var openingResult=opening==null ? null : (java.util.concurrent.CompletableFuture<?>)get(opening.getClass(),"result",opening);
                        openingSkipped=!((PreviewRenderer<?>)renderer).modelFitApplicable() && openingResult!=null && openingResult.isDone()
                                && ((PreviewAssets.NumericFitResult)openingResult.join()).status()==PreviewAssets.NumericFitStatus.UNAVAILABLE;
                        check(ReferenceBodyHost.submitted.isEmpty() && get(rendererType,"nativeFit",renderer)==null
                                && subject.numericLoads==0 && subject.numericCreates==0,
                                "real worldless host opens before any texture-ready numeric admission: "+surface);
                    } finally { client.level = level; }
                    subject.worldChanged();
                    subject.runtime.resourcesReloaded();
                    subject.drainWorkers();
                    sync.invoke(host,subject.runtime.view(854,480,0,0));
                    subject.drainWorkers();
                    int classifiers=FitCountingObservation.classifiers,copies=FitCountingObservation.copies;
                    for(int i=0;i<100;i++) {
                        draw.invoke(host,new ReferenceBodyHost.Graphics(),subject.runtime.view(854+i%3,480,i%11,i%17));
                        subject.drainWorkers();
                    }
                    check(subject.numericLoads==0 && subject.numericCreates==0 && FitCountingObservation.captures==captures
                                    && get(rendererType,"nativeFit",renderer)==null
                                    && FitCountingObservation.classifiers==classifiers && FitCountingObservation.copies==copies,
                            "early world entry before texture-ready admission must not create ambient numeric work: "+surface
                                    +" loads="+subject.numericLoads+" captures="+(FitCountingObservation.captures-captures));
                    check(openingSkipped,"canonical opening records actual native worldless applicability before texture readiness: "+surface);
                    earlyCases.add(Map.of("surface",surface,"phase","early-skip","hostFrames",100,"diskLoads",0,"captures",0,"firstCreates",0,
                            "loadedHandles",0,"nativeFitAdmissions",0));
                    skippedSource=subject.runtime.previewBodySource(subject.runtime.galleryPreviewCandidate().orElseGet(()->
                            subject.runtime.view(854,480,0,0).previews().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow()));
                    for(String method:List.of("releasePreviewAccessories","releasePreviewBody")) {
                        var release=hostType.getDeclaredMethod(method); release.setAccessible(true); release.invoke(host);
                    }
                    subject.runtime.closeScreen();
                    skins.retain(Set.of());
                    capes.retain(Set.of());
                    for(var previousKey:((Map<?,?>)hostType.getField("previewSkinKeys").get(host)).values())
                        check(skins.imageState(previousKey,null).status()==PreviewAssetCache.ImageStatus.UNKNOWN,
                                "new native screen opening does not inherit retired host cache entries");
                    subject.runtime.reopen();
                    subject.drainWorkers();
                    if(surface.equals("editor")) {
                        var preset=subject.runtime.snapshot().selectedPresetId().orElseThrow();
                        subject.runtime.dispatchWidget("gallery.preset."+preset+".more");
                        subject.runtime.dispatchWidget("gallery.preset."+preset+".edit");
                    } else if(surface.equals("providers")) subject.runtime.dispatchWidget("gallery.providers");
                    view=subject.runtime.view(854,480,0,0);
                    preview=view.previews().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow();
                    check(view.screenId().equals(screenId),"same runtime genuine surface reopened: "+surface);
                }
                if(mode.equals("worldless")) {
                var client = ReferenceBodyHost.RawClient.current;
                var level = client.level;
                java.util.concurrent.CompletableFuture<PreviewAssets.NumericFitResult> skipped;
                try {
                    client.level=null;
                    skipped=subject.runtime.preparePreviewModelFit(preview,(PreviewRenderer<?>)renderer,selected);
                    check(skipped.isDone() && skipped.join().status()==PreviewAssets.NumericFitStatus.UNAVAILABLE,"real native worldless admission skips: "+surface+" done="+skipped.isDone()+" result="+skipped.getNow(null)+" applicable="+((PreviewRenderer<?>)renderer).modelFitApplicable());
                } finally { client.level=level; }
                Object skippedOwner = get(ClientRuntime.class,"numericFitOperation",subject.runtime);
                int skippedCaptures=FitCountingObservation.captures;
                subject.worldChanged();
                subject.runtime.resourcesReloaded();
                subject.drainWorkers();
                var afterEvent=subject.runtime.view(854,480,0,0).previews().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow();
                check(subject.runtime.preparePreviewModelFit(afterEvent,(PreviewRenderer<?>)renderer,selected)==skipped,
                        "world/resource events must not renew semantic demand: "+surface);
                check(get(ClientRuntime.class,"numericFitOperation",subject.runtime)==skippedOwner,"retired skip tombstone remains semantic owner: "+surface);
                check(subject.numericLoads==0 && subject.numericCreates==0 && FitCountingObservation.captures==skippedCaptures,
                        "worldless lifecycle callbacks do no numeric work: "+surface);
                sync.invoke(host,subject.runtime.view(854,480,0,0)); subject.drainWorkers();
                sync.invoke(host,subject.runtime.view(854,480,0,0));
                draw.invoke(host,new ReferenceBodyHost.Graphics(),subject.runtime.view(854,480,0,0));
                int skipClassifiers=FitCountingObservation.classifiers, skipCopies=FitCountingObservation.copies;
                for(int i=0;i<100;i++) draw.invoke(host,new ReferenceBodyHost.Graphics(),subject.runtime.view(854+i%3,480,i%11,i%17));
                subject.drainWorkers();
                check(get(ClientRuntime.class,"numericFitOperation",subject.runtime)==skippedOwner && subject.numericLoads==0
                        && subject.numericCreates==0 && FitCountingObservation.captures==skippedCaptures
                        && FitCountingObservation.classifiers==skipClassifiers && FitCountingObservation.copies==skipCopies,
                        "100 actual host frames after worldless retirement create no ambient fit work: "+surface);
                System.out.println("SEMANTIC_WORLDLESS_PASS surface="+surface+" hostFrames=100 diskLoads=0 captures=0 creates=0");
                semanticCases.add(Map.of("surface",surface,"mode",mode,"hostFrames",100,"diskLoads",0,"captures",0,"firstCreates",0));
                for(String method:List.of("releasePreviewAccessories","releasePreviewBody")) {
                    var release=hostType.getDeclaredMethod(method); release.setAccessible(true); release.invoke(host);
                }
                return;
                }
                if (mode.startsWith("worldless-")) {
                    var measured = ReferenceBodyProbe.lastFit;
                    check(measured.fit().normalization() < 0.5, "genuine same-carrier FULL cache discriminates baked shrink");
                    var key = new PreviewModelFitStore.Key(subject.sessionAccount,ReferenceBodyProbe.contentKey(input).split("/")[0],com.naocraftlab.skins.core.model.SkinVariant.CLASSIC);
                    var store = new com.naocraftlab.skins.runtime.infrastructure.PreviewModelFitStorageAdapter(
                            new com.naocraftlab.skins.core.storage.NclSkinsStorage(root,new com.naocraftlab.skins.core.png.PngValidator(),java.time.Clock.systemUTC()));
                    var value = new PreviewModelFitStore.Value(measured.referenceHeight(),measured.referenceCenterX(),measured.referenceCenterY(),measured.referenceCenterZ(),measured.fit());
                    check(store.create(key,value,() -> true).status()==PreviewModelFitStore.Status.PRESENT && store.load(key).value().equals(value), "real same-content native numeric record seeded and freshly read");
                    var client=ReferenceBodyHost.RawClient.current;
                    var oldLevel=client.level;
                    int captures=FitCountingObservation.captures;
                    var noEffects=subject.noEffectsCheckpoint();
                    try (var fallback = new CpmBakedDisplayProbe.SelectedFallback()) {
                        ReferenceBodyHost.fallback=fallback;
                        client.level=null;
                        sync.invoke(host,view); subject.drainWorkers(); sync.invoke(host,subject.runtime.view(854,480,0,0));
                        NativePlayerSkinLifecycle.Registration pending=mode.equals("worldless-pending") ? NativePlayerSkinLifecycle.pending(input.id().toString()) : null;
                        try {
                            draw.invoke(host,new ReferenceBodyHost.Graphics(),subject.runtime.view(854,480,0,0));
                            check(((PreviewRenderer<?>)renderer).displayKind()==PreviewRenderer.DisplayKind.UNCONFIRMED,"actual fallback enqueue cannot announce display");
                            boolean positive=mode.equals("worldless-draw");
                            if (mode.equals("worldless-stale")) ((EditorPreviewSession)get(rendererType,"session",renderer)).invalidateDisplayedBody();
                            fallback.failDraw=mode.equals("worldless-failed");
                            Throwable failure=null;
                            try {
                                fallback.consume();
                                if(positive) {
                                    check(((PreviewRenderer<?>)renderer).displayKind()==PreviewRenderer.DisplayKind.UNCONFIRMED,
                                            "hidden own fallback cannot grant visible control authority");
                                    draw.invoke(host,new ReferenceBodyHost.Graphics(),subject.runtime.view(854,480,0,0));
                                    fallback.consume();
                                }
                            } catch(RuntimeException | Error thrown) { failure=thrown; }
                            if (fallback.failDraw) check(failure instanceof IllegalStateException && failure.getMessage().equals("synthetic selected fallback draw failure") && failure.getSuppressed().length==0,"exact own baked draw failure retained: "+failure+" draws="+fallback.primaryDraws+" completions="+fallback.completions);
                            else check(failure==null,"selected fallback native prepare/consumer succeeds: "+failure);
                            check(((PreviewRenderer<?>)renderer).displayKind()==(positive ? PreviewRenderer.DisplayKind.ORDINARY : PreviewRenderer.DisplayKind.UNCONFIRMED),"actual selected fallback display truth: "+mode);
                            if (positive) {
                                check(fallback.primaryDraws==2 && fallback.materialPasses==2 && fallback.completions==2 && fallback.source.equals(input.id().toString()),"actual compiled selected baked dispatcher/prepare/material/hidden and visible draw-terminal/completion");
                                var actualRequest=ReferenceBodyHost.lastRequest;
                                check(Math.abs(fallback.scale-0.97*actualRequest.height()/2.125*actualRequest.scale())<0.001,"cached FULL never shrinks actual baked scale operand");
                            } else {
                                check(fallback.completions==0 && fallback.materialPasses==0,"failed/stale/pending cannot complete ordinary or publish material");
                                check(fallback.primaryDraws==(fallback.failDraw?1:0),"strict pending/stale preparation guard and exact failed terminal");
                            }
                            check(subject.numericLoads==0 && subject.numericCreates==0 && FitCountingObservation.captures==captures && get(rendererType,"nativeFit",renderer)==null,"worldless ready texture never admits full numerical work");
                            check(store.load(key).value().equals(value),"baked fallback preserves stored FULL winner");
                            noEffects.run();
                            fallbackCases.add(Map.of("surface",surface,"mode",mode,"bakedInvocations",fallback.invocations,"drawTerminals",fallback.primaryDraws,"materials",fallback.materialPasses,"completions",fallback.completions,"nativeScale",fallback.scale,"storedFullFactor",value.fit().normalization(),"displayKind",((PreviewRenderer<?>)renderer).displayKind().name()));
                        } finally { if(pending!=null)pending.retire(); }
                    } finally { ReferenceBodyHost.fallback=null; client.level=oldLevel; }
                    for(String method:List.of("releasePreviewAccessories","releasePreviewBody")) { var release=hostType.getDeclaredMethod(method);release.setAccessible(true);release.invoke(host); }
                    return;
                }
                if(mode.startsWith("comparison-")) {
                    providersFallbackComparison(input,subject,host,sync,draw,renderer,selected,mode.substring("comparison-".length()));
                    return;
                }
                sync.invoke(host,view);
                ReferenceBodyHost.submitted.clear();
                if (surface.equals("providers")) {
                    var profile=(com.mojang.authlib.GameProfile)get(rendererType,"previewProfile",renderer);

                    CapeProjection.effectiveSkin(profile.id(),WORLD,input.id().toString(),SkinModel.CLASSIC,true);
                }
                draw.invoke(host,new ReferenceBodyHost.Graphics(),view);
                var controlledTime = new java.util.concurrent.atomic.AtomicLong();
                EditorPreviewSession deadlineSession = null;
                long originalStarted = 0;
                if (mode.startsWith("deadline-")) {
                    deadlineSession = (EditorPreviewSession)get(rendererType,"session",renderer);
                    originalStarted = (long)get(EditorPreviewSession.class,"bodyStarted",deadlineSession);
                    controlledTime.set(originalStarted);
                    set(EditorPreviewSession.class,"nanoTime",deadlineSession,(java.util.function.LongSupplier)controlledTime::get);
                    var galleryClock=get(rendererType,"gallery",renderer);
                    if(galleryClock!=null) set(EditorPreviewSession.Gallery.class,"clock",galleryClock,(java.util.function.LongSupplier)controlledTime::get);
                    check(deadlineSession.waitsForBody() == !surface.equals("providers"), "scoped original deadline policy: " + surface);
                    if (!surface.equals("providers")) check(deadlineSession.bodyStatus()==PreviewRenderer.BodyStatus.PENDING,
                            "original source is hidden and pending before texture/model release: " + surface);
                    controlledTime.set(originalStarted + java.time.Duration.ofSeconds(59).toNanos());
                }
                if (!mode.equals("early-worldless")) {
                    Object observer = hostType.getField("bodyObserver").get(host);
                    check(observer != null, "first pending texture frame retains actual body observer: " + surface);
                    if (!surface.equals("providers")) check(ReferenceBodyHost.submitted.isEmpty(), "pending texture submits no custom body: " + surface);
                    else check(get(ClientRuntime.class,"numericFitOperation",subject.runtime)!=null,"actual " + surface + " screen demand must reach numeric preparation owner");
                    for (int i=0;i<100;i++) draw.invoke(host,new ReferenceBodyHost.Graphics(),subject.runtime.view(854+i%3,480,0,0));
                    check(hostType.getField("bodyObserver").get(host) == observer, "100 frames preserve one current body observer: " + surface);
                }
                subject.drainWorkers();
                sync.invoke(host,subject.runtime.view(854,480,0,0));
                subject.drainWorkers();
                var profile = (com.mojang.authlib.GameProfile)get(rendererType,"previewProfile",renderer);

                var source = CapeProjection.effectiveSkin(profile.id(),WORLD,input.id().toString(),SkinModel.CLASSIC,true);
                call(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);
                var player = ReferenceBodyProbe.definitions.loadPlayer(profile.id(),com.tom.cpm.shared.definition.ModelDefinitionLoader.PLAYER_UNIQUE);
                var definition = player.getDefinitionFuture().get(5,java.util.concurrent.TimeUnit.SECONDS);
                definition.startResolve();
                long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (definition.getResolveState()==com.tom.cpm.shared.definition.ModelDefinition.ModelLoadingState.RESOLVING && System.nanoTime()<until) Thread.sleep(1);
                player.getModelDefinition();
                check(definition.doRender() && source.active(), "own exact native definition resolved: " + surface);
                ReferenceBodyHost.player = player;
                int captures = FitCountingObservation.captures;
                draw.invoke(host,new ReferenceBodyHost.Graphics(),subject.runtime.view(854,480,0,0));
                var operation = get(ClientRuntime.class,"numericFitOperation",subject.runtime);
                check(operation != null, "actual " + surface + " screen demand must reach numeric preparation owner");

                if(mode.equals("missing-required")) {
                    subject.drainWorkers();
                    for(int phase=0;phase<3;phase++) {
                        draw.invoke(host,new ReferenceBodyHost.Graphics(),subject.runtime.view(854,480,0,0)); subject.drainWorkers();
                    }
                    var settledProfile=(com.mojang.authlib.GameProfile)get(rendererType,"previewProfile",renderer);
                    CapeProjection.effectiveSkin(settledProfile.id(),WORLD,input.id().toString(),SkinModel.CLASSIC,true);
                    call(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);
                    var settledPlayer=ReferenceBodyProbe.definitions.loadPlayer(settledProfile.id(),com.tom.cpm.shared.definition.ModelDefinitionLoader.PLAYER_UNIQUE);
                    var settledDefinition=settledPlayer.getDefinitionFuture().get(5,java.util.concurrent.TimeUnit.SECONDS);
                    settledDefinition.startResolve();
                    long settledUntil=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                    while(settledDefinition.getResolveState()==com.tom.cpm.shared.definition.ModelDefinition.ModelLoadingState.RESOLVING && System.nanoTime()<settledUntil)Thread.sleep(1);
                    check(settledPlayer.getModelDefinition()!=null,"current fallback native source resolves through its actual cached player");
                    ReferenceBodyHost.player=settledPlayer;
                    ReferenceBodyHost.submitted.clear();
                    draw.invoke(host,new ReferenceBodyHost.Graphics(),subject.runtime.view(854,480,0,0));
                    var currentFallbackProfile=(com.mojang.authlib.GameProfile)get(rendererType,"previewProfile",renderer);
                    if(!settledProfile.equals(currentFallbackProfile)) {
                        CapeProjection.effectiveSkin(currentFallbackProfile.id(),WORLD,input.id().toString(),SkinModel.CLASSIC,true);
                        call(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);
                        var currentFallbackPlayer=ReferenceBodyProbe.definitions.loadPlayer(currentFallbackProfile.id(),com.tom.cpm.shared.definition.ModelDefinitionLoader.PLAYER_UNIQUE);
                        var currentFallbackDefinition=currentFallbackPlayer.getDefinitionFuture().get(5,java.util.concurrent.TimeUnit.SECONDS);
                        currentFallbackDefinition.startResolve();
                        long currentFallbackUntil=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                        while(currentFallbackDefinition.getResolveState()==com.tom.cpm.shared.definition.ModelDefinition.ModelLoadingState.RESOLVING && System.nanoTime()<currentFallbackUntil)Thread.sleep(1);
                        check(currentFallbackPlayer.getModelDefinition()!=null,"refined fallback source resolves through its own cached player");
                        ReferenceBodyHost.player=currentFallbackPlayer;
                        ReferenceBodyHost.submitted.clear();
                        draw.invoke(host,new ReferenceBodyHost.Graphics(),subject.runtime.view(854,480,0,0));
                    }
                    check(currentFallbackProfile.equals(get(rendererType,"previewProfile",renderer)),"fallback native pass belongs to the current refined owner");
                    check(!ReferenceBodyHost.submitted.isEmpty(), "missing required mod preserves original live renderer and native layers: " + surface);
                    Object livePip=ReferenceBodyHost.loader.loadClass(ReferenceBodyHost.GUI).getConstructor(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class).newInstance(minecraft.getEntityRenderDispatcher());
                    var liveConsumer=ReferenceBodyHost.loader.loadClass("com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame");
                    var liveState=ReferenceBodyHost.submitted.get(ReferenceBodyHost.submitted.size()-1);
                    ReferenceBodyProbe.reset();
                    try {
                        for(int pass=0;pass<2;pass++) {
                            if(pass>0) {
                                ReferenceBodyHost.submitted.clear();
                                draw.invoke(host,new ReferenceBodyHost.Graphics(),subject.runtime.view(854,480,0,0));
                                liveState=ReferenceBodyHost.submitted.get(ReferenceBodyHost.submitted.size()-1);
                            }
                            var imageSession=(EditorPreviewSession)get(rendererType,"session",renderer);
                            boolean visibleBefore=imageSession.imageVisible();
                            try(var frame=(AutoCloseable)liveConsumer.getMethod("open", Object.class).invoke(null, liveConsumer)) {
                                var gui=new GuiRenderState();
                                ReferenceBodyHost.loader.loadClass(ReferenceBodyHost.GUI).getMethod("prepare",PictureInPictureRenderState.class,GuiRenderState.class,FeatureRenderDispatcher.class,int.class).invoke(livePip,liveState,gui,ReferenceBodyProbe.features,1);
                                var blits=new ArrayList<BlitRenderState>();gui.forEachElement(e -> {if(e instanceof BlitRenderState b)blits.add(b);},GuiRenderState.TraverseRange.ALL);
                                check(blits.size()==1 && ReferenceBodyProbe.verticesCount>0 && ReferenceBodyProbe.exactDefinitionBindings>0,"missing Ears keeps genuine native CPM geometry/material and installed layers");
                                check(imageSession.imageVisible()==visibleBefore,"LIVE_HIDDEN_IMAGE_AFTER_CONSUMER: native prepare cannot publish image readiness");
                                check(((PreviewRenderer<?>)renderer).displayKind()==PreviewRenderer.DisplayKind.UNCONFIRMED,"LIVE_VISIBLE_ACK_AFTER_CONSUMER: native prepare cannot publish control authority");
                                PipMatrix.consume(blits.get(0));liveConsumer.getMethod("submitted").invoke(null);
                            }
                            check(((PreviewRenderer<?>)renderer).displayKind()==PreviewRenderer.DisplayKind.UNCONFIRMED,"LIVE_VISIBLE_ACK_AFTER_FENCE: consumer submission remains provisional");
                            vertices.endFrame();PipCpuBackend.signal();
                        }
                    } finally { ((AutoCloseable)livePip).close(); vertices.endFrame();PipCpuBackend.signal(); }
                    check(((PreviewRenderer<?>)renderer).displayKind()==PreviewRenderer.DisplayKind.ORDINARY,"partial original live path uses baked indicator, not false FULL");
                    var liveRequest=ReferenceBodyHost.lastRequest;
                    check(Math.abs(liveState.scale()-0.97*liveRequest.height()/2.125*liveRequest.scale())<0.001,"missing required mod keeps baked normalization factor one");
                    fallbackCases.add(Map.of("surface",surface,"mode","missing-ears-live","vertices",ReferenceBodyProbe.verticesCount,"definitionBindings",ReferenceBodyProbe.exactDefinitionBindings,"displayKind","ORDINARY","nativeScale",liveState.scale(),"firstCreates",0));
                    operation=get(ClientRuntime.class,"numericFitOperation",subject.runtime);
                    var result=(java.util.concurrent.CompletableFuture<?>)get(operation.getClass(),"result",operation);
                    var requiredProfile=(com.mojang.authlib.GameProfile)get(rendererType,"previewProfile",renderer);
                    check(CapeProjection.previewBodyRequirements(requiredProfile.id()).ears()==PreviewModelFrames.Requirement.REQUIRED,
                            "same exact genuine carrier requires Ears: "+surface);
                    check(result.isDone() && ((PreviewAssets.NumericFitResult)result.join()).value()==null
                                    && (result.join() instanceof PreviewAssets.NumericFitResult value
                                        && (value.status()==PreviewAssets.NumericFitStatus.UNAVAILABLE || value.status()==PreviewAssets.NumericFitStatus.RETIRED))
                                    && ReferenceBodyHost.missingRequiredChecks>missingChecks,
                            "actual required Ears absence terminates without partial FULL: "+surface+" result="+result.getNow(null));
                    int loads=subject.numericLoads, classifiers=FitCountingObservation.classifiers, copies=FitCountingObservation.copies;
                    ReferenceBodyHost.loader.withoutEars=false;
                    subject.worldChanged(); subject.runtime.resourcesReloaded(); subject.drainWorkers();
                    for(int i=0;i<100;i++) draw.invoke(host,new ReferenceBodyHost.Graphics(),subject.runtime.view(854+i%3,480,i%11,i%17));
                    subject.drainWorkers();
                    check(get(ClientRuntime.class,"numericFitOperation",subject.runtime)==operation && subject.numericLoads==loads
                            && subject.numericCreates==0 && FitCountingObservation.captures==captures
                            && FitCountingObservation.classifiers==classifiers && FitCountingObservation.copies==copies,
                            "missing required consumer cannot ambient-retry after availability/resource/world events: "+surface);
                    System.out.println("SEMANTIC_MISSING_REQUIRED_PASS surface="+surface+" hostFrames=100 captures=0 creates=0");
                    semanticCases.add(Map.of("surface",surface,"mode",mode,"hostFrames",100,"diskLoads",loads,"captures",0,"firstCreates",0,
                            "actualRequiredMissingChecks",ReferenceBodyHost.missingRequiredChecks-missingChecks));
                    for(String method:List.of("releasePreviewAccessories","releasePreviewBody")) {
                        var release=hostType.getDeclaredMethod(method); release.setAccessible(true); release.invoke(host);
                    }
                    return;
                }

                Object pip = ReferenceBodyHost.loader.loadClass(ReferenceBodyHost.GUI).getConstructor(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class)
                        .newInstance(minecraft.getEntityRenderDispatcher());
                var consumer = ReferenceBodyHost.loader.loadClass("com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame");
                int passes = 0;
                boolean deadlineReleased = false;
                try {
                    until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
                    while (System.nanoTime() < until) {
                        var currentProfile=(com.mojang.authlib.GameProfile)get(rendererType,"previewProfile",renderer);
                        if (!currentProfile.equals(profile)) {
                            profile=currentProfile;
                            CapeProjection.effectiveSkin(profile.id(),WORLD,input.id().toString(),SkinModel.CLASSIC,true);
                            call(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);
                            player=ReferenceBodyProbe.definitions.loadPlayer(profile.id(),com.tom.cpm.shared.definition.ModelDefinitionLoader.PLAYER_UNIQUE);
                            definition=player.getDefinitionFuture().get(5,java.util.concurrent.TimeUnit.SECONDS); definition.startResolve();
                            while(definition.getResolveState()==com.tom.cpm.shared.definition.ModelDefinition.ModelLoadingState.RESOLVING && System.nanoTime()<until) Thread.sleep(1);
                            player.getModelDefinition(); ReferenceBodyHost.player=player;
                        }
                        ReferenceBodyHost.submitted.clear();
                        draw.invoke(host,new ReferenceBodyHost.Graphics(),subject.runtime.view(854,480,0,0));
                        if (!mode.startsWith("deadline-")) subject.drainWorkers();
                        for (var state : List.copyOf(ReferenceBodyHost.submitted)) {
                            try (var frame = (AutoCloseable)consumer.getMethod("open", Object.class).invoke(null, consumer)) {
                                var gui = new GuiRenderState(); ReferenceBodyProbe.reset();
                                ReferenceBodyHost.loader.loadClass(ReferenceBodyHost.GUI).getMethod("prepare",PictureInPictureRenderState.class,GuiRenderState.class,FeatureRenderDispatcher.class,int.class)
                                        .invoke(pip,state,gui,ReferenceBodyProbe.features,1);
                                var blits = new ArrayList<BlitRenderState>();
                                gui.forEachElement(e -> { if(e instanceof BlitRenderState b) blits.add(b); },GuiRenderState.TraverseRange.ALL);
                                check(blits.size()==1 && ReferenceBodyProbe.verticesCount>0,"own genuine native BODY/material drain: " + surface);
                                PipMatrix.consume(blits.get(0)); consumer.getMethod("submitted").invoke(null); passes++;
                            }
                            vertices.endFrame(); PipCpuBackend.signal();
                        }
                        if (mode.startsWith("deadline-") && !deadlineReleased) {
                            var nativeFit = get(rendererType,"nativeFit",renderer);
                            var measured = nativeFit == null ? null : (java.util.concurrent.CompletableFuture<?>)get(nativeFit.getClass(),"result",nativeFit);
                            if (measured != null && measured.isDone() && measured.getNow(null) != null) {
                                check(subject.numericCreates==0 && !((Deque<?>)get(GalleryNativePassFixture.class,"workers",subject)).isEmpty(),
                                        "actual completed native fit is held before real queued first-create: " + surface);
                                if (!surface.equals("providers")) {
                                    var actualSession=(EditorPreviewSession)get(rendererType,"session",renderer);
                                    check(actualSession.bodyStatus()==PreviewRenderer.BodyStatus.PENDING
                                            && (long)get(EditorPreviewSession.class,"bodyStarted",actualSession)==originalStarted,
                                            "native model/measurement ready never resets original hidden source deadline: " + surface);
                                }
                                long expires=originalStarted + java.time.Duration.ofSeconds(60).toNanos();
                                if (mode.equals("deadline-before-publication")) subject.beforeNumericPublication=() -> controlledTime.set(expires);
                                else controlledTime.set(expires);
                                subject.drainWorkers();
                                if (!surface.equals("providers")) {
                                    var owner=get(ClientRuntime.class,"numericFitOperation",subject.runtime);
                                    var result=(java.util.concurrent.CompletableFuture<?>)get(owner.getClass(),"result",owner);
                                    int expectedAttempts=mode.equals("deadline-before-publication")?1:0;
                                    check(subject.numericCreates==expectedAttempts && result.isDone()
                                            && ((PreviewAssets.NumericFitResult)result.join()).status()==PreviewAssets.NumericFitStatus.RETIRED
                                            && ((EditorPreviewSession)get(rendererType,"session",renderer)).bodyStatus()==PreviewRenderer.BodyStatus.TIMED_OUT
                                            && ((PreviewRenderer<?>)renderer).displayKind()!=PreviewRenderer.DisplayKind.CPM,
                                            "original 60s rejects actual late first-create and BODY ACK: " + surface);
                                    check(subject.durableNumericFit().status()==PreviewModelFitStore.Status.ABSENT,
                                            "fresh actual storage reader confirms no late durable record: " + surface);
                                    check(subject.numericPublicationChecks==(mode.equals("deadline-before-publication")?2:0),
                                            "deadline checked at exact real immutable prepublication guard: " + surface);
                                    int loads=subject.numericLoads, classifiers=FitCountingObservation.classifiers, copies=FitCountingObservation.copies;
                                    var timedPreview=subject.runtime.galleryPreviewCandidate().orElseGet(()->subject.runtime.view(854,480,0,0).previews().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow());
                                    for(int i=0;i<100;i++) check(subject.runtime.preparePreviewModelFit(timedPreview,(PreviewRenderer<?>)renderer,selected)==result,
                                            "terminal original demand cannot retry: " + surface);
                                    subject.drainWorkers();
                                    check(subject.numericCreates==expectedAttempts && subject.numericLoads==loads && get(ClientRuntime.class,"numericFitOperation",subject.runtime)==owner
                                            && FitCountingObservation.classifiers==classifiers && FitCountingObservation.copies==copies
                                            && FitCountingObservation.captures-captures==2,
                                            "late body cannot re-enter or retry after original timeout: " + surface);
                                    lifecycleCases.add(Map.of("surface",surface,"phase",mode,"captures",2,"firstCreates",0,
                                            "nativePasses",passes,"createAttempts",expectedAttempts,"publicationChecks",subject.numericPublicationChecks,"postTimeoutDemandCalls",100,"status","RETIRED","bodyStatus","TIMED_OUT"));
                                    System.out.println("LIFECYCLE_ORIGINAL_DEADLINE_PASS surface="+surface+" nativePasses="+passes+" captures=2 creates=0 ack=false");
                                    for(String method:List.of("releasePreviewAccessories","releasePreviewBody")) {
                                        var release=hostType.getDeclaredMethod(method); release.setAccessible(true); release.invoke(host);
                                    }
                                    if (mode.equals("deadline-before-publication")) return;
                                    subject.runtime.closeScreen(); subject.runtime.reopen(); subject.drainWorkers();
                                    if(surface.equals("editor")) {
                                        var preset=subject.runtime.snapshot().selectedPresetId().orElseThrow();
                                        subject.runtime.dispatchWidget("gallery.preset."+preset+".more");
                                        subject.runtime.dispatchWidget("gallery.preset."+preset+".edit");
                                    }
                                    view=subject.runtime.view(854,480,0,0);
                                    sync.invoke(host,view); subject.drainWorkers();
                                    draw.invoke(host,new ReferenceBodyHost.Graphics(),view); subject.drainWorkers();
                                    profile=(com.mojang.authlib.GameProfile)get(rendererType,"previewProfile",renderer);

                                    CapeProjection.effectiveSkin(profile.id(),WORLD,input.id().toString(),SkinModel.CLASSIC,true);
                                    call(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);
                                    player=ReferenceBodyProbe.definitions.loadPlayer(profile.id(),com.tom.cpm.shared.definition.ModelDefinitionLoader.PLAYER_UNIQUE);
                                    definition=player.getDefinitionFuture().get(5,java.util.concurrent.TimeUnit.SECONDS); definition.startResolve();
                                    while(definition.getResolveState()==com.tom.cpm.shared.definition.ModelDefinition.ModelLoadingState.RESOLVING && System.nanoTime()<until) Thread.sleep(1);
                                    player.getModelDefinition(); ReferenceBodyHost.player=player;
                                    mode="timeout-reopened";
                                    captures=FitCountingObservation.captures; passes=0; deadlineReleased=true;
                                    continue;
                                }
                                check(subject.numericCreates==1,"Providers does not inherit Editor/Gallery 60s timeout");
                                deadlineReleased = true;
                            } else subject.drainWorkers();
                        } else subject.drainWorkers();
                        if (((PreviewRenderer<?>)renderer).displayKind()==PreviewRenderer.DisplayKind.CPM) break;
                        call(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);
                    }
                    check(((PreviewRenderer<?>)renderer).displayKind()==PreviewRenderer.DisplayKind.CPM,"actual current BODY ACK after fit: " + surface);

                    check(FitCountingObservation.captures-captures==2,"one genuine reference/custom measurement pair: " + surface + " captures=" + (FitCountingObservation.captures-captures));
                    check(passes>=3,"measurement and genuine own displayed BODY each executed: " + surface);
                    var currentPreview=subject.runtime.galleryPreviewCandidate().orElseGet(()->subject.runtime.view(854,480,0,0).previews().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow());
                    var stored=subject.runtime.preparePreviewModelFit(currentPreview,(PreviewRenderer<?>)renderer,selected).join();
                    check(stored.status()==PreviewAssets.NumericFitStatus.STORED && stored.value()!=null,"own durable numeric publication: "+surface);
                    int classifiers=FitCountingObservation.classifiers,copies=FitCountingObservation.copies,loads=subject.numericLoads,creates=subject.numericCreates;
                    if(mode.equals("positive")) {
                        for(int[] size:List.of(new int[]{240,180},new int[]{240,240},new int[]{1280,240},new int[]{854,480})) {
                            var resized=subject.runtime.view(size[0],size[1],0,0);
                            var projected=resized.previews().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow();
                            check(Math.abs(projected.scale()-1.08F)<0.000001,"readiness/resize retains chosen logical zoom: "+surface);
                            float layout=surface.equals("gallery")?1F:Math.max(.68F,Math.min(2F,(size[1]-78)/(.97F*size[1])));
                            check(Math.abs(projected.layoutScale()-layout)<0.000001,"surface owns layout independently of zoom: "+surface);
                            var request=ViewHostPolicy.previewRequest(projected,Optional.of(selected),Optional.of(selected)).orElseThrow();
                            ReferenceBodyHost.submitted.clear();
                            draw.invoke(host,new ReferenceBodyHost.Graphics(),resized);
                            check(ReferenceBodyHost.submitted.size()==1,"compiled host submits actual resized main request: "+surface);
                            var state=ReferenceBodyHost.submitted.get(0);
                            var value=stored.value();var fit=value.fit();double factor=fit.normalization();
                            ReferenceBodyProbe.near(state.scale(),.97*request.height()/2.125*layout*1.08F*factor,"layout/user zoom/normalization applied exactly once using actual request height");
                            double pitch=CenteredPipPreviewTransform.pitchRadians(request.pitchDegrees());
                            var original=CenteredPlayerPreviewGeometry.centeredEntityTranslation(CenteredPlayerPreviewGeometry.STANDING_PLAYER_HEIGHT,(float)pitch);
                            ReferenceBodyProbe.near(state.translation().x(),PreviewStageGeometry.modelOffsetX(request,state.scale()),"original X placement at actual surface anchor");
                            ReferenceBodyProbe.near(state.translation().y(),original.y()+PreviewStageGeometry.modelOffsetY(request,state.scale()),"original Y placement at actual surface anchor");
                            ReferenceBodyProbe.near(state.translation().z(),original.z(),"original Z placement at actual surface pitch");
                            try(var frame=(AutoCloseable)consumer.getMethod("open", Object.class).invoke(null, consumer)) {
                                var gui=new GuiRenderState();ReferenceBodyProbe.reset();
                                ReferenceBodyHost.loader.loadClass(ReferenceBodyHost.GUI).getMethod("prepare",PictureInPictureRenderState.class,GuiRenderState.class,FeatureRenderDispatcher.class,int.class).invoke(pip,state,gui,ReferenceBodyProbe.features,1);
                                var blits=new ArrayList<BlitRenderState>();gui.forEachElement(e->{if(e instanceof BlitRenderState b)blits.add(b);},GuiRenderState.TraverseRange.ALL);
                                check(blits.size()==1&&ReferenceBodyProbe.verticesCount>0&&ReferenceBodyProbe.exactDefinitionBindings>0,"resized request reaches genuine own native material/geometry");
                                PipMatrix.consume(blits.get(0));consumer.getMethod("submitted").invoke(null);
                            }
                            vertices.endFrame();PipCpuBackend.signal();
                        }
                        if(!surface.equals("gallery")) {
                            String tab=surface.equals("editor")?"editor.tab.cape":"providers.tab.CAPE";
                            var beforeTab=subject.runtime.view(854,480,0,0);
                            check(beforeTab.widget(tab).filter(w->w.enabled()).isPresent(),"actual tab action is admitted");
                            subject.runtime.dispatchWidget(tab);
                            var afterTab=subject.runtime.view(854,480,0,0);
                            check(!beforeTab.widget(tab).equals(afterTab.widget(tab)),"actual tab switch changes selected presentation");
                            check(afterTab.previews().stream().filter(p->p.id().equals(id)).allMatch(p->Math.abs(p.scale()-1.08F)<0.000001),"tab switch retains logical zoom");
                        }
                        System.out.println("INITIAL_UNIT_ZOOM_NATIVE_PASS surface="+surface+" logical=1->1.08 layouts=4 centerXYZ=once ownMaterial=true");
                    }
                    for(int i=0;i<100;i++) {
                        ReferenceBodyHost.submitted.clear();
                        draw.invoke(host,new ReferenceBodyHost.Graphics(),subject.runtime.view(854+i%3,480,i%11,i%17));
                    }
                    subject.drainWorkers();
                    check(classifiers==FitCountingObservation.classifiers && copies==FitCountingObservation.copies
                            && FitCountingObservation.captures-captures==2 && loads==subject.numericLoads && creates==subject.numericCreates,
                            "100 actual screen frames/hover/resize add no classification/copy/geometry/disk work: "+surface);
                    check(creates==1,"one real durable first-create: "+surface);
                    var row=Map.<String,Object>of("surface",surface,"texturePendingFrames",surface.equals("providers")?0:101,
                            "readyFrames",100,"captures",FitCountingObservation.captures-captures,"nativePasses",passes,
                            "diskLoads",loads,"firstCreates",creates,"displayKind","CPM");
                    if(mode.equals("early-worldless")) {
                        var nativeFit=get(rendererType,"nativeFit",renderer);
                        check(!skippedSource.equals(subject.runtime.previewBodySource(currentPreview))
                                && subject.runtime.previewBodySource(currentPreview).equals(get(nativeFit.getClass(),"source",nativeFit))
                                && ((PreviewRenderer.ModelFitPreparation)nativeFit).current(),
                                "reopened source owns fresh current native fit, never resurrects skipped demand: "+surface);
                        var reopened=new HashMap<>(row); reopened.remove("texturePendingFrames");
                        reopened.put("phase","same-runtime-reopen"); reopened.put("sameRuntime",true); reopened.put("newNativeOwner",true); earlyCases.add(reopened);
                    } else if (mode.equals("timeout-reopened")) {
                        var next=new HashMap<>(row); next.put("phase","timeout-reopened");
                        var fit=get(rendererType,"nativeFit",renderer);
                        check(((PreviewRenderer.ModelFitPreparation)fit).current()
                                && subject.runtime.previewBodySource(currentPreview).equals(get(fit.getClass(),"source",fit)),
                                "next legitimate opening owns current fitted native material and BODY ACK: " + surface);
                        next.put("sameRuntime",true); lifecycleCases.add(next);
                    } else if (mode.startsWith("deadline-")) {
                        var noTimeout=new HashMap<>(row); noTimeout.put("phase","providers-no-new-timeout"); lifecycleCases.add(noTimeout);
                    } else cases.add(row);
                    System.out.println("SCREEN_DEMAND_CASE mode="+mode+" " + new com.google.gson.Gson().toJson(row));
                } finally { ((AutoCloseable)pip).close(); }
                for (String method : List.of("releasePreviewAccessories","releasePreviewBody")) {
                    var release = hostType.getDeclaredMethod(method); release.setAccessible(true); release.invoke(host);
                }
            } finally { ((AutoCloseable)renderer).close(); }
        }
    }
}
