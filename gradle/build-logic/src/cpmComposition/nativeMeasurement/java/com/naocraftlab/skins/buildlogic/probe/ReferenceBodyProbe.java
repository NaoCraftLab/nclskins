package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.runtime.*;
import com.naocraftlab.skins.compat.cpm.*;
import com.tom.cpm.shared.definition.*;
import com.tom.cpm.client.*;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.state.*;
import net.minecraft.client.renderer.feature.*;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.client.renderer.state.gui.*;
import net.minecraft.client.renderer.state.gui.pip.*;
import static com.naocraftlab.skins.buildlogic.probe.PipMaterialProbe.*;

public final class ReferenceBodyProbe {
    public static boolean neutral=true;
    static boolean custom;
    static PreviewRenderer.ModelFitMeasurement lastFit;
    static boolean warm;
    static boolean restart;
    static Path originalOutput;
    static Path numericRoot;
    static GalleryNativePassFixture numericFixture;
    static final List<Map<String,Object>> warmCases = new ArrayList<>();
    static final Map<String,String> numericRoots = new TreeMap<>();
    static String contentKey(com.naocraftlab.skins.probe.EarsJoinBridge.Processed input) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.content()))+"/"+(slim?"SLIM":"CLASSIC");
    }
    static com.tom.cpm.shared.definition.ModelDefinitionLoader<UUID> definitions;
    static PreviewRenderer.CapeMode accessory=PreviewRenderer.CapeMode.OFF;
    public static boolean slim;
    static net.minecraft.client.renderer.entity.EntityRendererProvider.Context rendererContext()throws Exception{
        var context=(net.minecraft.client.renderer.entity.EntityRendererProvider.Context)call(CpmCurrentNativeGeometryProbe.class,"rendererContext",new Class<?>[]{},null);
        if(slim){@SuppressWarnings("unchecked")var roots=(Map<net.minecraft.client.model.geom.ModelLayerLocation,net.minecraft.client.model.geom.builders.LayerDefinition>)get(net.minecraft.client.model.geom.EntityModelSet.class,"roots",context.getModelSet());var all=com.google.common.collect.ImmutableMap.<net.minecraft.client.model.geom.ModelLayerLocation,net.minecraft.client.model.geom.builders.LayerDefinition>builder();all.putAll(roots);all.put(net.minecraft.client.model.geom.ModelLayers.PLAYER_SLIM,net.minecraft.client.model.geom.builders.LayerDefinition.create(net.minecraft.client.model.player.PlayerModel.createMesh(net.minecraft.client.model.geom.builders.CubeDeformation.NONE,true),64,64));net.minecraft.client.model.geom.ModelLayers.PLAYER_SLIM_ARMOR.putFrom(net.minecraft.client.model.player.PlayerModel.createArmorMeshSet(new net.minecraft.client.model.geom.builders.CubeDeformation(0.5F),new net.minecraft.client.model.geom.builders.CubeDeformation(1)).map(mesh->net.minecraft.client.model.geom.builders.LayerDefinition.create(mesh,64,32)),all);set(net.minecraft.client.renderer.entity.EntityRendererProvider.Context.class,"modelSet",context,new net.minecraft.client.model.geom.EntityModelSet(all.buildOrThrow()));}return context;
    }
    public static float age=31;
    static boolean inLayer;
    static int earsEntries,verticesCount,exactDefinitionBindings;
    static double minY,maxY,displayMin,displayMax;
    static org.joml.Matrix4f inverse;
    static final List<String> positions=new ArrayList<>();
    static final List<Map<String,Object>> cases=new ArrayList<>();
    static final Set<String> sampled=new TreeSet<>();
    public static void materialSelection(AvatarRenderState state){
        if(state instanceof ReferenceBodyHost.RawAvatar raw&&raw.definition!=null){
            var expected=CpmNeutralBody.select(ReferenceBodyHost.player,raw.definition);
            if(expected==null){check(!manager.isBound(avatar.getModel()),"reference scope selects actual ordinary mesh without assigning live player");return;}
            check(manager.getHolderSafe(avatar.getModel(),null,h->h.swappedIn&&h.def==expected&&h.def instanceof OwnedCpmDefinition&&((OwnedCpmDefinition)h.def).source().active(),false,false),"exact production selected owned definition/material");exactDefinitionBindings++;
        }
    }
    public static void root(PoseStack pose){inverse=new org.joml.Matrix4f(pose.last().pose()).invert();}
    public static void enterLayer(){inLayer=true;}
    public static void leaveLayer(){inLayer=false;}
    public static void earsEntry(){check(inLayer,"genuine installed Ears native layer entered");earsEntries++;}
    public static void mesh(MeshData data){
        ReferenceBodyHost.mesh(data);if(data==null)return;
        var state=data.drawState();var bytes=data.vertexBuffer().order(java.nio.ByteOrder.nativeOrder());var format=state.format();int stride=format.getVertexSize(),offset=format.getElement("Position").offset();
        for(int i=0;i<state.vertexCount();i++){
            int o=i*stride+offset;var point=new org.joml.Vector3f(bytes.getFloat(o),bytes.getFloat(o+4),bytes.getFloat(o+8));displayMin=Math.min(displayMin,point.y);displayMax=Math.max(displayMax,point.y);inverse.transformPosition(point);
            check(Float.isFinite(point.x)&&Float.isFinite(point.y)&&Float.isFinite(point.z),"finite actual native pre-camera vertex");minY=Math.min(minY,point.y);maxY=Math.max(maxY,point.y);positions.add(point.x+","+point.y+","+point.z);
        }verticesCount+=state.vertexCount();
    }
    static String digest()throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(String.join(";",positions).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    static void reset(){ReferenceVertices.counts.clear();ReferenceVertices.models.clear();ReferenceVertices.leave();ReferenceBodyHost.meshVertices=0;ReferenceBodyHost.meshMin=Double.POSITIVE_INFINITY;ReferenceBodyHost.meshMax=Double.NEGATIVE_INFINITY;ReferenceBodyHost.ownMaterialAcquisitions=0;earsEntries=verticesCount=exactDefinitionBindings=0;positions.clear();minY=displayMin=Double.POSITIVE_INFINITY;maxY=displayMax=Double.NEGATIVE_INFINITY;}
    static Map<String,Object> draw(String label,com.naocraftlab.skins.probe.EarsJoinBridge.Processed input,boolean authoredNeutral,float time,String remove)throws Exception{
        neutral=authoredNeutral;age=time;avatar=new Avatar(rendererContext());check(avatar.layerCount()==11,"owned full genuine renderer installed eleven layers");rawRenderer();com.naocraftlab.skins.probe.PipNativeInputs.select(input);
        var selected=new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle(input.id().toString(),64,64),slim?SkinModel.SLIM:SkinModel.CLASSIC,Optional.empty(),accessory,OuterLayerVisibility.allVisible(),true,false,true);
        ReferenceBodyHost.expectedMaterial=input.texture().getTextureView();ReferenceBodyHost.appearance=selected;ReferenceBodyHost.player=null;ReferenceBodyHost.initialize();
        var owner=ReferenceBodyHost.renderer;var type=owner.getClass();
        if(!warm) numericRoot=Files.createTempDirectory("native-numeric-");
        if(restart) numericRoot=Path.of(Objects.requireNonNull(numericRoots.get(contentKey(input)),"restart exact content record"));
        if(!warm) numericRoots.put(contentKey(input),numericRoot.toString());
        numericFixture=GalleryNativePassFixture.numeric(selected,input.content(),numericRoot);
        check((boolean)type.getMethod("prepareGalleryBody",String.class,boolean.class,BooleanSupplier.class).invoke(owner,label,custom,(BooleanSupplier)input::current),"actual current host accepts exact selected source");
        com.tom.cpm.shared.config.Player<?> livePlayer=null;ModelDefinition live=null;OwnedCpmDefinition isolated=null;Object future=null;String before=null;
        if(custom){
            var profile=(com.mojang.authlib.GameProfile)get(type,"previewProfile",owner);var source=CapeProjection.effectiveSkin(profile.id(),WORLD,input.id().toString(),SkinModel.CLASSIC,true);call(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);livePlayer=definitions.loadPlayer(profile.id(),ModelDefinitionLoader.PLAYER_UNIQUE);live=livePlayer.getDefinitionFuture().get(5,java.util.concurrent.TimeUnit.SECONDS);live.startResolve();long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while(live.getResolveState()==ModelDefinition.ModelLoadingState.RESOLVING&&System.nanoTime()<until)Thread.sleep(1);livePlayer.getModelDefinition();check(live instanceof OwnedCpmDefinition&&((OwnedCpmDefinition)live).source()==source&&live.doRender(),"actual producer cached player exact source readiness");future=livePlayer.getDefinitionFuture();before=(String)call(CpmNeutralGeometryProbe.class,"liveState",new Class<?>[]{ModelDefinition.class},null,live);
            ReferenceBodyHost.player=livePlayer;
        }
        int earsReferenceBefore=EarsScopeObservation.reference,earsNeutralBefore=EarsScopeObservation.neutral;
        int captureBefore=FitCountingObservation.captures,decodeBefore=FitCountingObservation.decodes;
        productionFit(owner,type,label,selected,input);
        if(input.texture().earsFeatures().enabled&&!warm)check(EarsScopeObservation.reference>earsReferenceBefore&&EarsScopeObservation.neutral>earsNeutralBefore,"genuine installed Ears delegate consumes scoped ordinary and neutral production passes");
        if(custom){
            var policyPreparation = ((PreviewRenderer<?>) owner).prepareModelFit(label, selected).orElseThrow();

            com.tom.cpm.shared.config.Player.setEnableRendering(false);ReferenceBodyHost.submitted.clear();
            check(!policyPreparation.current() && !java.util.concurrent.CompletableFuture.supplyAsync(policyPreparation::current).get(), "CPM denied policy revokes native fit authority on client and worker even beside ready Ears");
            type.getMethod("renderGallery",ReferenceBodyHost.Graphics.class,String.class,PreviewRenderer.PreviewRequest.class).invoke(owner,new ReferenceBodyHost.Graphics(),label,new PreviewRenderer.PreviewRequest(selected,0,0,160,180,0,0,1,PreviewRenderer.PreviewIntent.EDITOR_DRAFT));
            check(ReferenceBodyHost.submitted.isEmpty(),"available numeric fit cannot bypass current CPM rendering policy");
            com.tom.cpm.shared.config.Player.setEnableRendering(true);
            check(policyPreparation.current() && java.util.concurrent.CompletableFuture.supplyAsync(policyPreparation::current).get(), "restored actual CPM readiness is rechecked without a cached policy decision");
            System.out.println("EARS_CPM_POLICY_PASS " + label + " deniedClientAndWorker=true noLiveBody=true restoredReadiness=true");
        }
        CpmNeutralBody neutralControl=!warm&&custom&&accessory==PreviewRenderer.CapeMode.OFF?new CpmNeutralBody(livePlayer,(OwnedCpmDefinition)live):null;
        if(neutralControl!=null){long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while(neutralControl.body().status()==PreviewRenderer.BodyStatus.PENDING&&System.nanoTime()<until){call(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);Thread.sleep(1);}check(neutralControl.body().status()==PreviewRenderer.BodyStatus.READY,"independently owned neutral consumer control ready");}

        ReferenceBodyHost.submitted.clear();type.getMethod("renderGallery",ReferenceBodyHost.Graphics.class,String.class,PreviewRenderer.PreviewRequest.class).invoke(owner,new ReferenceBodyHost.Graphics(),label,new PreviewRenderer.PreviewRequest(selected,0,0,160,180,0,0,1,PreviewRenderer.PreviewIntent.EDITOR_DRAFT));
        check(ReferenceBodyHost.submitted.size()==1,"one actual current host native captured state");var pip=ReferenceBodyHost.submitted.get(0);((AvatarRenderState)pip.renderState()).ageInTicks=time;if(accessory!=PreviewRenderer.CapeMode.OFF){var s=(AvatarRenderState)pip.renderState();s.skin=new net.minecraft.world.entity.player.PlayerSkin(s.skin.body(),new net.minecraft.core.ClientAsset.DownloadedTexture(net.minecraft.resources.Identifier.parse("cpm:textures/template/cape.png"),"synthetic:public-cpm-material"),null,s.skin.model(),false);}if(accessory==PreviewRenderer.CapeMode.ELYTRA)ReferenceEquipment.configure((AvatarRenderState)pip.renderState());Object publicPip=ReferenceBodyHost.loader.loadClass(ReferenceBodyHost.GUI).getConstructor(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class).newInstance(minecraft.getEntityRenderDispatcher());
        if(accessory==PreviewRenderer.CapeMode.OFF){var nativeState=(AvatarRenderState)pip.renderState();nativeState.skin=new net.minecraft.world.entity.player.PlayerSkin(nativeState.skin.body(),null,null,nativeState.skin.model(),false);}
        var consumer=ReferenceBodyHost.loader.loadClass("com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame");reset();int drawStart=PipCpuBackend.drawRecords.size();
        int neutralCalls=ReferenceNeutral.calls,capeExclusions=ReferenceNeutral.capeExclusions,wingExclusions=ReferenceNeutral.wingExclusions;
        var engine=(com.tom.cpm.shared.animation.AnimationEngine)get(com.tom.cpm.shared.model.render.ModelRenderManager.class,"animEngine",manager);var clockBefore=List.of(get(com.tom.cpm.shared.animation.AnimationEngine.class,"tickCounter",engine),get(com.tom.cpm.shared.animation.AnimationEngine.class,"partial",engine),get(com.tom.cpm.shared.animation.AnimationEngine.class,"resetCounter",engine));
        try(var frame=(AutoCloseable)consumer.getMethod("open", Object.class).invoke(null, consumer);var scope=neutralControl==null?null:neutralControl.open(false)){
            var gui=new GuiRenderState();gui.addPicturesInPictureState(pip);var order=new ArrayList<PictureInPictureRenderState>();gui.forEachPictureInPicture(order::add);check(order.size()==1&&order.get(0)==pip,"actual native GUI retains current captured state");
            ReferenceBodyHost.loader.loadClass(ReferenceBodyHost.GUI).getMethod("prepare",PictureInPictureRenderState.class,GuiRenderState.class,FeatureRenderDispatcher.class,int.class).invoke(publicPip,pip,gui,features,1);
            check(!manager.isBound(avatar.getModel()),"genuine upstream post unbound before outer consumer scope closes");
            var blits=new ArrayList<BlitRenderState>();gui.forEachElement(e->{if(e instanceof BlitRenderState b)blits.add(b);},GuiRenderState.TraverseRange.ALL);
            check(blits.size()==1&&verticesCount>0&&maxY>minY&&earsEntries==1&&(custom?exactDefinitionBindings>0:ReferenceBodyHost.ownMaterialAcquisitions>0),"real complete BODY/current source/material/native mesh and installed Ears positive: blits="+blits.size()+" vertices="+verticesCount+" ears="+earsEntries+" acquisitions="+ReferenceBodyHost.ownMaterialAcquisitions);
            var output=blits.get(0).textureSetup().texure0();check(PipCpuBackend.drawRecords.subList(drawStart,PipCpuBackend.drawRecords.size()).stream().anyMatch(d->d.target()==output&&!d.sampled().isEmpty()),"real native material drain into own leased output");PipMatrix.consume(blits.get(0));consumer.getMethod("submitted").invoke(null);
        }
        if(isolated!=null){check(clockBefore.equals(List.of(get(com.tom.cpm.shared.animation.AnimationEngine.class,"tickCounter",engine),get(com.tom.cpm.shared.animation.AnimationEngine.class,"partial",engine),get(com.tom.cpm.shared.animation.AnimationEngine.class,"resetCounter",engine))),"exact native engine clock fields untouched by isolated full pass");if(accessory==PreviewRenderer.CapeMode.OFF){check(ReferenceNeutral.capeExclusions-capeExclusions==1&&ReferenceNeutral.wingExclusions-wingExclusions==1,"exact native managed layer slice each executed once");check(ReferenceVertices.counts.keySet().stream().noneMatch(k->k.contains("CAPE")||k.contains("ELYTRA")||k.contains("CapeModel")||k.contains("ElytraModel")),"no native/accessory-root/fallback vertices enter body measurement");}check(ReferenceNeutral.calls>neutralCalls&&ReferenceNeutral.active==null,"normal chained neutral hook exercised and scope restored");check(livePlayer.getDefinitionFuture()==future&&livePlayer.getModelDefinition0()==live&&before.equals(call(CpmNeutralGeometryProbe.class,"liveState",new Class<?>[]{ModelDefinition.class},null,live)),"full neutral engine preserves original live assignment/future and pose");}
        if(custom){
            var value=lastFit.fit();near(pip.scale(),0.97*180/2.125*value.normalization(),"current native consumer applies height factor exactly once");
            if(!warm&&accessory==PreviewRenderer.CapeMode.OFF){near(displayMax-displayMin,lastFit.fit().bodyHeight()*pip.scale(),"actual neutral native output consumes numeric factor once");
            near((displayMax+displayMin)/2,90+pip.scale()*(CenteredPlayerPreviewGeometry.modernEntityTranslationY(CenteredPlayerPreviewGeometry.STANDING_PLAYER_HEIGHT)-value.centerY()),"neutral current native consumer preserves authored vertical placement");
            check(displayMax-displayMin<=number(reference,"displayHeight")+0.001,"height-only cap and no small upscaling");}
            near(pip.translation().x(),0,"authored X offset is not recentered");near(pip.translation().z(),0,"authored Z offset is not recentered");
            var marker=ReferenceBodyHost.marker;float extent=(float)marker.getMethod("nclskins$depthExtent").invoke(pip.renderState());check(extent>PreviewDepthEnvelope.forRequest(PreviewRenderer.PreviewIntent.EDITOR_DRAFT,pip.scale(),0,PreviewRenderer.CapeMode.OFF),"measured numeric depth extends preserved ordinary accessory envelope");
            if(neutralControl!=null){check(livePlayer.getDefinitionFuture()==future&&livePlayer.getModelDefinition0()==live&&before.equals(call(CpmNeutralGeometryProbe.class,"liveState",new Class<?>[]{ModelDefinition.class},null,live)),"consumer neutral control leaves live assignment/future/pose unchanged");neutralControl.close();}
        }
        var row=new LinkedHashMap<String,Object>();row.put("sourceLocation",input.id().toString());row.put("capeLayerExclusions",ReferenceNeutral.capeExclusions-capeExclusions);row.put("wingsLayerExclusions",ReferenceNeutral.wingExclusions-wingExclusions);row.put("liveAssignmentAndPosePreserved",custom);row.put("engineClockUnchanged",custom);row.put("variant",slim?"SLIM":"CLASSIC");row.put("displayHeight",displayMax-displayMin);row.put("displayCenterY",(displayMax+displayMin)/2);row.put("nativePipScale",pip.scale());row.put("nativePipTranslationY",pip.translation().y());row.put("factor",lastFit.fit().normalization());row.put("sensitivity",ReferenceNormalization.sensitivity);row.put("nativeModelVertices",new TreeMap<>(ReferenceVertices.counts));row.put("accessory",accessory);row.put("neutralHookCalls",ReferenceNeutral.calls-neutralCalls);row.put("isolatedDefinition",isolated!=null);row.put("case",label);row.put("neutral",neutral);row.put("age",age);row.put("removedFeature",remove);row.put("vertices",verticesCount);row.put("minY",minY);row.put("maxY",maxY);row.put("height",maxY-minY);row.put("centerY",(maxY+minY)/2);row.put("geometrySha256",digest());row.put("ownSourceAcquisitions",ReferenceBodyHost.ownMaterialAcquisitions);row.put("nativeDraws",PipCpuBackend.drawRecords.size()-drawStart);row.put("sourceCurrent",input.current());if(custom){
            vertices.endFrame();PipCpuBackend.signal();var raw=(ReferenceBodyHost.RawAvatar)pip.renderState();raw.definition=live;((PlayerRenderStateAccess)(Object)raw).cpm$setPlayer((com.tom.cpm.shared.config.Player)livePlayer);reset();int callsBefore=ReferenceNeutral.calls;
            try(var frame=(AutoCloseable)consumer.getMethod("open", Object.class).invoke(null, consumer)){
                var gui=new GuiRenderState();ReferenceBodyHost.loader.loadClass(ReferenceBodyHost.GUI).getMethod("prepare",PictureInPictureRenderState.class,GuiRenderState.class,FeatureRenderDispatcher.class,int.class).invoke(publicPip,pip,gui,features,1);var blits=new ArrayList<BlitRenderState>();gui.forEachElement(e->{if(e instanceof BlitRenderState b)blits.add(b);},GuiRenderState.TraverseRange.ALL);check(blits.size()==1&&verticesCount>0&&exactDefinitionBindings>0&&ReferenceNeutral.calls==callsBefore,"same original live source full native pipeline continues without neutral interception");PipMatrix.consume(blits.get(0));consumer.getMethod("submitted").invoke(null);
            }
            boolean animated=!before.equals(call(CpmNeutralGeometryProbe.class,"liveState",new Class<?>[]{ModelDefinition.class},null,live));
            check(livePlayer.getDefinitionFuture()==future&&livePlayer.getModelDefinition0()==live,"subsequent live assignment/future identity unchanged");
            if(label.contains("tall"))check(animated,"subsequent genuine live engine animates original assignment after neutral measurement");
            check(((PreviewRenderer<?>)owner).displayKind()==PreviewRenderer.DisplayKind.CPM,"current native complete live BODY acknowledges CPM only after coherent measured fit");
            row.put("subsequentLive",Map.of("nativeVertices",verticesCount,"neutralCalls",ReferenceNeutral.calls-callsBefore,"assignmentUnchanged",true,"poseAnimated",animated,"displayKind","CPM"));
        }
        if(input.texture().earsFeatures().enabled){
            check(EarsScopeObservation.live>0,"native current consumer returns to original live Ears features");
            near(pip.scale(),0.97*180/2.125*lastFit.fit().normalization(),"Ears current consumer applies numeric normalization exactly once");
            row.put("earsProductionReferenceReads",EarsScopeObservation.reference-earsReferenceBefore);row.put("earsProductionNeutralReads",EarsScopeObservation.neutral-earsNeutralBefore);
            row.put("earsOriginalLiveFeatures",true);
            row.put("earsNativeVertices",((Number)row.get("vertices")).intValue()-ReferenceVertices.counts.values().stream().mapToInt(Integer::intValue).sum());
            if(!custom){
                vertices.endFrame();PipCpuBackend.signal();((AvatarRenderState)pip.renderState()).ageInTicks=73;
                int neutralReads=EarsScopeObservation.neutral;reset();
                try(var frame=(AutoCloseable)consumer.getMethod("open", Object.class).invoke(null, consumer)){
                    var gui=new GuiRenderState();ReferenceBodyHost.loader.loadClass(ReferenceBodyHost.GUI).getMethod("prepare",PictureInPictureRenderState.class,GuiRenderState.class,FeatureRenderDispatcher.class,int.class).invoke(publicPip,pip,gui,features,1);
                    var blits=new ArrayList<BlitRenderState>();gui.forEachElement(e->{if(e instanceof BlitRenderState b)blits.add(b);},GuiRenderState.TraverseRange.ALL);
                    check(blits.size()==1&&verticesCount>288&&EarsScopeObservation.neutral==neutralReads&&!digest().equals(row.get("geometrySha256")),"same original Ears source naturally animates at subsequent native age without neutral interception");
                    PipMatrix.consume(blits.get(0));consumer.getMethod("submitted").invoke(null);
                }
                check(((PreviewRenderer<?>)owner).displayKind()==PreviewRenderer.DisplayKind.ORDINARY,"current own Ears live BODY acknowledges ordinary consumer");
                row.put("subsequentEarsLive",Map.of("nativeVertices",verticesCount,"poseAnimated",true,"neutralReads",EarsScopeObservation.neutral-neutralReads,"displayKind","ORDINARY"));
            }
        }
        row.put("nativePipTranslationX",pip.translation().x());row.put("nativePipTranslationZ",pip.translation().z());
        row.put("nativeDepth",ReferenceBodyHost.marker.getMethod("nclskins$depthExtent").invoke(pip.renderState()));
        row.put("numericFit",lastFit);row.put("contentKey",contentKey(input));
        row.put("layoutTransforms",layoutTransforms(owner,type,label,selected));
        if(warm){
            if(input.texture().earsFeatures().enabled) assertStableAnalysis(owner,type,label,selected,"warm");
            check(captureBefore==FitCountingObservation.captures&&decodeBefore==FitCountingObservation.decodes,"entire warm BODY/material/ACK and layout sequence performs no capture or neutral decode");
            row.put("newCaptures",FitCountingObservation.captures-captureBefore);row.put("newNeutralDecodes",FitCountingObservation.decodes-decodeBefore);
        }
        if(warm) warmCases.add(row); else cases.add(row);System.out.println((warm?"WARM_BODY_CASE ":"REFERENCE_BODY_CASE ")+new com.google.gson.Gson().toJson(row));
        var completedPreparation=((PreviewRenderer<?>)owner).prepareModelFit(label,selected).orElseThrow();
        var retiredProfile=(com.mojang.authlib.GameProfile)get(type,"previewProfile",owner);CapeProjection.retireSkin(retiredProfile.id());
        check(!completedPreparation.current()&&!java.util.concurrent.CompletableFuture.supplyAsync(completedPreparation::current).get(2,java.util.concurrent.TimeUnit.SECONDS),"exact source retirement closes numeric admission on both client and worker");
        numericFixture.close();numericFixture=null;
        type.getMethod("close").invoke(owner);vertices.endFrame();PipCpuBackend.signal();if(isolated!=null)isolated.cleanup();call(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);((AutoCloseable)publicPip).close();
        if(!warm){
            warm=true;
            try{
                var loaded=draw(label+"/warm",input,authoredNeutral,time,remove);
                for(String key:List.of("factor","nativePipScale","nativePipTranslationX","nativePipTranslationY","nativePipTranslationZ","nativeDepth")) near(number(loaded,key),number(row,key),"durable warm matches actual cold "+key);
                check(loaded.get("layoutTransforms").equals(row.get("layoutTransforms")),"warm replays exact numeric placement/depth at changed layout zoom pitch");
            }finally{warm=false;}
        }
        return row;
    }
    static void productionFit(Object owner,Class<?> type,String label,PreviewRenderer.PreviewAppearance selected,com.naocraftlab.skins.probe.EarsJoinBridge.Processed input)throws Exception{
        var profile=(com.mojang.authlib.GameProfile)get(type,"previewProfile",owner);

        var source=CapeProjection.effectiveSkin(profile.id(),WORLD,input.id().toString(),selected.model(),true);
        var requirements = CapeProjection.previewBodyRequirements(profile.id());
        check(source!=null&&source.active()&&requirements.admitted()
                && (requirements.cpm()==PreviewModelFrames.Requirement.REQUIRED)==custom
                && (requirements.ears()==PreviewModelFrames.Requirement.REQUIRED)==input.texture().earsFeatures().enabled,"exact source independent body eligibility");
        var fitting=((PreviewRenderer<?>)owner).prepareModelFit(label,selected).orElseThrow();
        var persisted=numericFixture.runtime.preparePreviewModelFit(numericFixture.preview(),fitting);
        numericFixture.drainWorkers();
        System.out.println("NUMERIC_JOIN "+label+" current="+fitting.current()+" source="+source.active()+" persisted="+persisted.getNow(null));
        if(warm){
            var loaded=persisted.getNow(null);
            check(loaded!=null&&loaded.status()==PreviewAssets.NumericFitStatus.STORED&&loaded.value()!=null,"fresh runtime reads actual durable native result");
            lastFit=new PreviewRenderer.ModelFitMeasurement(loaded.value().referenceHeight(),loaded.value().referenceCenterX(),loaded.value().referenceCenterY(),loaded.value().referenceCenterZ(),loaded.value().fit());
            int captures=FitCountingObservation.captures,decodes=FitCountingObservation.decodes;
            ReferenceBodyHost.submitted.clear();
            type.getMethod("renderGallery",ReferenceBodyHost.Graphics.class,String.class,PreviewRenderer.PreviewRequest.class).invoke(owner,new ReferenceBodyHost.Graphics(),label,new PreviewRenderer.PreviewRequest(selected,0,0,160,180,0,0,1,PreviewRenderer.PreviewIntent.EDITOR_DRAFT));
            check(ReferenceBodyHost.submitted.size()==1,"warm current native consumer submits without measurement");
            var state=ReferenceBodyHost.submitted.get(0).renderState();
            var context=ReferenceBodyHost.marker.getMethod("nclskins$previewContext").invoke(state);
            check(get(context.getClass(),"capture",context)==null,"warm native consumer must not queue hidden reference geometry pass");
            check(captures==FitCountingObservation.captures&&decodes==FitCountingObservation.decodes,"warm native consumer constructs no geometry capture or neutral decode");
            System.out.println("WARM_FIT "+label+" "+new com.google.gson.Gson().toJson(loaded.value())+" captures=0 decodes=0");
            return;
        }
        var measured=fitting.measure();
        if(input.texture().earsFeatures().enabled)System.out.println("EARS_ADMISSION requirements="+requirements+" owned="+CapeProjection.ownsSkinTexture(source,input.texture())+" ticket="+NativePlayerSkinLifecycle.managedState(input.id().toString())+" prepared="+(com.naocraftlab.skins.compat.client.identifier.ears.EarsNeutralBody.prepare(source)!=null));
        if(input.texture().earsFeatures().enabled){var image=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,64,64,source.pixels(),0,64);System.out.println("EARS_EVIDENCE "+new com.naocraftlab.skins.core.compatibility.SkinFeatureAnalyzer().analyze(image));}
        var actualEngine=(com.tom.cpm.shared.animation.AnimationEngine)get(com.tom.cpm.shared.model.render.ModelRenderManager.class,"animEngine",manager);
        var engineFields=List.of(get(com.tom.cpm.shared.animation.AnimationEngine.class,"tickCounter",actualEngine),get(com.tom.cpm.shared.animation.AnimationEngine.class,"partial",actualEngine),get(com.tom.cpm.shared.animation.AnimationEngine.class,"resetCounter",actualEngine));
        Object originalFuture=custom?ReferenceBodyHost.player.getDefinitionFuture():null;
        ModelDefinition original=custom?ReferenceBodyHost.player.getModelDefinition0():null;
        String before=custom?(String)call(CpmNeutralGeometryProbe.class,"liveState",new Class<?>[]{ModelDefinition.class},null,original):null;
        Object pip=ReferenceBodyHost.loader.loadClass(ReferenceBodyHost.GUI).getConstructor(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class).newInstance(minecraft.getEntityRenderDispatcher());
        var consumer=ReferenceBodyHost.loader.loadClass("com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame");
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);int passes=0;
        while(!measured.isDone()&&System.nanoTime()<deadline){
            call(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);ReferenceBodyHost.submitted.clear();
            type.getMethod("renderGallery",ReferenceBodyHost.Graphics.class,String.class,PreviewRenderer.PreviewRequest.class).invoke(owner,new ReferenceBodyHost.Graphics(),label,new PreviewRenderer.PreviewRequest(selected,0,0,160,180,0,0,1,PreviewRenderer.PreviewIntent.EDITOR_DRAFT));
            if(ReferenceBodyHost.submitted.isEmpty()){Thread.sleep(1);continue;}
            check(ReferenceBodyHost.submitted.size()==1,"one actual queued neutral state");
            try(var frame=(AutoCloseable)consumer.getMethod("open", Object.class).invoke(null, consumer)){
                var gui=new GuiRenderState();reset();
                ReferenceBodyHost.loader.loadClass(ReferenceBodyHost.GUI).getMethod("prepare",PictureInPictureRenderState.class,GuiRenderState.class,FeatureRenderDispatcher.class,int.class).invoke(pip,ReferenceBodyHost.submitted.get(0),gui,features,1);
                var blits=new ArrayList<BlitRenderState>();gui.forEachElement(e->{if(e instanceof BlitRenderState b)blits.add(b);},GuiRenderState.TraverseRange.ALL);
                check(blits.size()==1&&verticesCount>0,"actual hidden native mesh/material drain");
                check(ReferenceVertices.counts.keySet().stream().noneMatch(k->k.contains("CAPE")||k.contains("ELYTRA")||k.contains("CapeModel")||k.contains("ElytraModel")),"whole native CAPE/ELYTRA excluded from production measurement");
                System.out.println("PRODUCTION_CAPTURE "+label+" pass="+passes+" vertices="+ReferenceVertices.counts);
PipMatrix.consume(blits.get(0));consumer.getMethod("submitted").invoke(null);passes++;
            }vertices.endFrame();PipCpuBackend.signal();
        }
        var value=measured.getNow(null);check(value!=null&&value.fit().bodyHeight()>0,"actual production geometry yields numeric fit");
        if(input.texture().earsFeatures().enabled&&!custom)check(value.fit().bodyHeight()>value.referenceHeight()+0.1,"genuine Ears BODY details extend native ordinary reference");
        check(passes==(custom||input.texture().earsFeatures().enabled?2:1),"one reference and one independent owned neutral pass; no per-frame geometry");
        if(custom){check(ReferenceBodyHost.player.getDefinitionFuture()==originalFuture&&ReferenceBodyHost.player.getModelDefinition0()==original&&before.equals(call(CpmNeutralGeometryProbe.class,"liveState",new Class<?>[]{ModelDefinition.class},null,original)),"production neutral preserves exact live future assignment and pose bits");check(CpmNeutralBody.select(ReferenceBodyHost.player,original)==original,"production neutral scope restored");}
        check(engineFields.equals(List.of(get(com.tom.cpm.shared.animation.AnimationEngine.class,"tickCounter",actualEngine),get(com.tom.cpm.shared.animation.AnimationEngine.class,"partial",actualEngine),get(com.tom.cpm.shared.animation.AnimationEngine.class,"resetCounter",actualEngine))),"production neutral measurement does not mutate global engine clock");
        check(java.util.concurrent.CompletableFuture.supplyAsync(fitting::current).get(2,java.util.concurrent.TimeUnit.SECONDS),"common currentness guard is worker-callable without native client getter");
        var neutralOwner=custom?get(fitting.getClass(),"neutral",fitting):null;
        OwnedCpmDefinition measuredDefinition=null;
        if(neutralOwner!=null){var future=(java.util.concurrent.CompletableFuture<?>)get(CpmNeutralBody.class,"decoded",neutralOwner);measuredDefinition=(OwnedCpmDefinition)future.getNow(null);check(measuredDefinition!=original&&measuredDefinition.source()==source&&measuredDefinition.getPlayerObj()==ReferenceBodyHost.player,"measurement is independently decoded exact-source definition, no live assignment swap");check((int)get(OwnedCpmDefinition.class,"users",measuredDefinition)==0,"all actual deferred measurement readers drained before numeric publication");}
        check(((PreviewRenderer<?>)owner).displayKind()==PreviewRenderer.DisplayKind.UNCONFIRMED,"hidden production normalization never displays before fit coherence");
        lastFit=value;numericFixture.drainWorkers();
        check(persisted.isDone()&&persisted.getNow(null).status()==PreviewAssets.NumericFitStatus.STORED,"actual common runtime writes durable native result");
        if(input.texture().earsFeatures().enabled) assertStableAnalysis(owner,type,label,selected,"success");
        if(measuredDefinition!=null)check((boolean)get(OwnedCpmDefinition.class,"retired",measuredDefinition)&&(boolean)get(OwnedCpmDefinition.class,"freed",measuredDefinition),"numeric publication retires and frees owned neutral resources after actual reader drain");
        System.out.println("PRODUCTION_FIT "+label+" "+new com.google.gson.Gson().toJson(value));
        ((AutoCloseable)pip).close();
    }
    static void rejectedCpmFit(Path path, com.naocraftlab.skins.core.png.CpmCarrierReader.Status expected) throws Exception {
        String label = "ears-cpm-" + expected.name().toLowerCase(java.util.Locale.ROOT);
        var image = javax.imageio.ImageIO.read(path.toFile());
        image.setRGB(0, 0, 0xff010b53);
        image.setRGB(1, 0, expected == com.naocraftlab.skins.core.png.CpmCarrierReader.Status.MALFORMED ? 0xff000001 : 0xff000002);
        image.setRGB(2, 0, 0xff000000);
        Path carrier = path.resolveSibling(label + ".png");
        javax.imageio.ImageIO.write(image, "PNG", carrier.toFile());
        try (var input = com.naocraftlab.skins.probe.PipNativeInputs.process(carrier)) {
            avatar = new Avatar(rendererContext()); rawRenderer();
            com.naocraftlab.skins.probe.PipNativeInputs.select(input);
            var selected = new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle(input.id().toString(),64,64), SkinModel.CLASSIC, Optional.empty(), PreviewRenderer.CapeMode.OFF, OuterLayerVisibility.allVisible(), true, false, true);
            ReferenceBodyHost.expectedMaterial = input.texture().getTextureView();
            ReferenceBodyHost.appearance = selected; ReferenceBodyHost.player = null; ReferenceBodyHost.initialize();
            var owner = ReferenceBodyHost.renderer; var type = owner.getClass();
            check((boolean) type.getMethod("prepareGalleryBody", String.class, boolean.class, BooleanSupplier.class).invoke(owner, label, false, (BooleanSupplier) input::current), "rejected CPM source reaches actual native owner");
            var profile = (com.mojang.authlib.GameProfile) get(type, "previewProfile", owner);

            var source = CapeProjection.effectiveSkin(profile.id(), WORLD, input.id().toString(), SkinModel.CLASSIC, true);
            var processed = new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_ARGB);
            processed.setRGB(0,0,64,64,source.pixels(),0,64);
            check(new com.naocraftlab.skins.core.png.CpmCarrierReader().read(processed).status() == expected, "actual processed CPM classification " + expected);
            check(input.texture().earsFeatures().enabled && com.naocraftlab.skins.compat.client.identifier.ears.EarsNeutralBody.prepare(source) != null, "genuine processed Ears ready beside rejected CPM " + expected);
            var requirements = CapeProjection.previewBodyRequirements(profile.id());
            check(!requirements.admitted() && requirements.ears() == PreviewModelFrames.Requirement.REQUIRED
                    && requirements.cpm() == (expected == com.naocraftlab.skins.core.png.CpmCarrierReader.Status.MALFORMED ? PreviewModelFrames.Requirement.DENIED : PreviewModelFrames.Requirement.UNKNOWN), "independent typed consumer requirements " + expected);
            var preparation = ((PreviewRenderer<?>) owner).prepareModelFit(label, selected).orElseThrow();
            var measured = preparation.measure();
            var persistence = NativeFitPersistenceFixture.begin(preparation, Files.readAllBytes(carrier));
            Object pip = ReferenceBodyHost.loader.loadClass(ReferenceBodyHost.GUI).getConstructor(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class).newInstance(minecraft.getEntityRenderDispatcher());
            var consumer = ReferenceBodyHost.loader.loadClass("com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame");
            try {
                for (int pass = 0; pass < 3 && !measured.isDone(); pass++) {
                    ReferenceBodyHost.submitted.clear();
                    type.getMethod("renderGallery",ReferenceBodyHost.Graphics.class,String.class,PreviewRenderer.PreviewRequest.class).invoke(owner,new ReferenceBodyHost.Graphics(),label,new PreviewRenderer.PreviewRequest(selected,0,0,160,180,0,0,1,PreviewRenderer.PreviewIntent.EDITOR_DRAFT));
                    for (var state : ReferenceBodyHost.submitted) try (var frame = (AutoCloseable) consumer.getMethod("open", Object.class).invoke(null, consumer)) {
                        var gui = new GuiRenderState(); reset();
                        ReferenceBodyHost.loader.loadClass(ReferenceBodyHost.GUI).getMethod("prepare",PictureInPictureRenderState.class,GuiRenderState.class,FeatureRenderDispatcher.class,int.class).invoke(pip,state,gui,features,1);
                        consumer.getMethod("submitted").invoke(null);
                    }
                    vertices.endFrame(); PipCpuBackend.signal();
                }
                System.out.println("EARS_CPM_REQUIREMENTS_WITNESS " + expected + " done=" + measured.isDone() + " measurement=" + new com.google.gson.Gson().toJson(measured.getNow(null)) + " firstCreates=" + persistence.creates().get());
                check(measured.isDone() && measured.getNow(null) == null, "rejected CPM cannot publish partial FULL despite genuine Ears readiness: " + expected);
                assertStableAnalysis(owner,type,label,selected,"rejected-"+expected);
                check(persistence.loads().get() == 1 && persistence.result().isDone() && persistence.creates().get() == 0 && persistence.result().getNow(null).value() == null, "rejected CPM never reaches actual first-create: " + expected);
                int beforeReplacement = FitCountingObservation.classifiers;
                var replacement = CapeProjection.effectiveSkin(profile.id(), java.util.UUID.randomUUID(), input.id().toString(), SkinModel.CLASSIC, true);
                check(replacement != source && !source.active() && !preparation.current(), "replacement retires exact old source and authority");
                var replacementRequirements = CapeProjection.previewBodyRequirements(profile.id());
                check(replacementRequirements.equals(requirements) && FitCountingObservation.classifiers == beforeReplacement + 1, "replacement independently classifies its own carrier");
                check(CapeProjection.previewBodyRequirements(profile.id()).equals(replacementRequirements) && FitCountingObservation.classifiers == beforeReplacement + 1, "replacement retains its own immutable facts");
                System.out.println("FIT_REPLACEMENT " + expected + " classifier=" + beforeReplacement + "->" + FitCountingObservation.classifiers);
                System.out.println("EARS_CPM_REQUIREMENTS_PASS " + expected + " genuineEarsReady=true full=false firstCreates=" + persistence.creates().get());
            } finally {
                type.getMethod("close").invoke(owner); ((AutoCloseable) pip).close();
                vertices.endFrame(); PipCpuBackend.signal();
            }
        }
    }
    static void assertStableAnalysis(Object owner,Class<?> type,String label,PreviewRenderer.PreviewAppearance selected,String phase)throws Exception{
        int classifiers=FitCountingObservation.classifiers,copies=FitCountingObservation.copies;
        check(classifiers>0 && copies>0,"actual classifier and Ears copy observers reached before "+phase);
        for(int frame=0;frame<100;frame++){
            ReferenceBodyHost.submitted.clear();
            type.getMethod("renderGallery",ReferenceBodyHost.Graphics.class,String.class,PreviewRenderer.PreviewRequest.class).invoke(owner,new ReferenceBodyHost.Graphics(),label,new PreviewRenderer.PreviewRequest(selected,0,0,160,180,0,0,1,PreviewRenderer.PreviewIntent.EDITOR_DRAFT));
        }
        System.out.println("FIT_COUNT "+phase+" classifiers="+classifiers+"->"+FitCountingObservation.classifiers+" copies="+copies+"->"+FitCountingObservation.copies);
        check(classifiers==FitCountingObservation.classifiers && copies==FitCountingObservation.copies,"same-source frames must not reclassify or construct neutral Ears copies: "+phase);
    }
    static Map<String,Object> reference;
    static void pendingFit(Path path,boolean cpm,boolean cached)throws Exception{
        String label=(cpm?"combined-pending-native":"ears-pending-native")+(cached?"/warm":"");
        var registry=(com.naocraftlab.skins.compat.client.identifier.IdentifierTextureRegistry)call(com.naocraftlab.skins.probe.EarsProbe.class,"start",new Class<?>[]{byte[].class},null,Files.readAllBytes(path));
        try(registry){
            var id=(net.minecraft.resources.Identifier)get(com.naocraftlab.skins.probe.EarsProbe.class,"source",null);
            var ticket=NativePlayerSkinLifecycle.registration(id.toString()).orElseThrow();
            check(ticket.state()==NativePlayerSkinLifecycle.State.PENDING,"genuine held native processing initially pending");
            custom=cpm;avatar=new Avatar(rendererContext());rawRenderer();
            var selected=new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle(id.toString(),64,64),SkinModel.CLASSIC,Optional.empty(),PreviewRenderer.CapeMode.OFF,OuterLayerVisibility.allVisible(),true,false,true);
            int captures=FitCountingObservation.captures,decodes=FitCountingObservation.decodes;
            if(cached){
                String key=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))+"/CLASSIC";
                numericRoot=Path.of(Objects.requireNonNull(numericRoots.get(key),"cold-produced pending warm record"));
                numericFixture=GalleryNativePassFixture.numeric(selected,Files.readAllBytes(path),numericRoot);
            }
            ReferenceBodyHost.appearance=selected;ReferenceBodyHost.player=null;ReferenceBodyHost.initialize();var owner=ReferenceBodyHost.renderer;var type=owner.getClass();
            check((boolean)type.getMethod("prepareGalleryBody",String.class,boolean.class,BooleanSupplier.class).invoke(owner,label,cpm,(BooleanSupplier)()->NativePlayerSkinLifecycle.registration(id.toString()).orElse(null)==ticket),"pending current source opens one native owner");
            var preparation=((PreviewRenderer<?>)owner).prepareModelFit(label,selected).orElseThrow();
            var future=cached?(java.util.concurrent.CompletableFuture<?>)get(preparation.getClass(),"result",preparation):preparation.measure();
            check(!future.isDone()&&preparation.current(),"pending processing does not publish ordinary partial fit");
            if(cached){
                var loaded=numericFixture.runtime.preparePreviewModelFit(numericFixture.preview(),preparation);numericFixture.drainWorkers();
                check(loaded.getNow(null)!=null&&loaded.getNow(null).status()==PreviewAssets.NumericFitStatus.STORED,"pending native owner obtains actual durable cached numbers");
                check(((PreviewRenderer<?>)owner).displayKind()==PreviewRenderer.DisplayKind.UNCONFIRMED&&preparation.current()&&!future.isDone(),"cached numbers cannot grant BODY authority before actual native processing");
            }
            ((Runnable)call(com.naocraftlab.skins.probe.EarsProbe.class,"queued",new Class<?>[]{},null)).run();
            var input=com.naocraftlab.skins.probe.EarsJoinBridge.processed(registry,id);com.naocraftlab.skins.probe.PipNativeInputs.select(input);
            ReferenceBodyHost.expectedMaterial=input.texture().getTextureView();
            if(cpm){
                var profile=(com.mojang.authlib.GameProfile)get(type,"previewProfile",owner);CapeProjection.effectiveSkin(profile.id(),WORLD,id.toString(),SkinModel.CLASSIC,true);call(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);
                var player=definitions.loadPlayer(profile.id(),ModelDefinitionLoader.PLAYER_UNIQUE);var definition=player.getDefinitionFuture().get(5,java.util.concurrent.TimeUnit.SECONDS);definition.startResolve();long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while(definition.getResolveState()==ModelDefinition.ModelLoadingState.RESOLVING&&System.nanoTime()<until)Thread.sleep(1);player.getModelDefinition();check(definition.doRender(),"combined pending source resolves its genuine CPM definition");ReferenceBodyHost.player=player;
            }
            if(!cached){numericRoot=Files.createTempDirectory("native-pending-numeric-");numericFixture=GalleryNativePassFixture.numeric(selected,input.content(),numericRoot);}
            warm=cached;
            productionFit(owner,type,label,selected,input);
            warm=false;
            check(((PreviewRenderer<?>)owner).prepareModelFit(label,selected).orElseThrow()==preparation&&(cached?!future.isDone():future.isDone()&&future.getNow(null)!=null),"same opening and exact pending preparation completes real native numeric fit");
            if(cached){
                Object pip=ReferenceBodyHost.loader.loadClass(ReferenceBodyHost.GUI).getConstructor(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class).newInstance(minecraft.getEntityRenderDispatcher());
                var consumer=ReferenceBodyHost.loader.loadClass("com.naocraftlab.skins.compat.client.identifier.preview.PreviewConsumerFrame");reset();
                try(var frame=(AutoCloseable)consumer.getMethod("open", Object.class).invoke(null, consumer)){
                    var gui=new GuiRenderState();
                    ReferenceBodyHost.loader.loadClass(ReferenceBodyHost.GUI).getMethod("prepare",PictureInPictureRenderState.class,GuiRenderState.class,FeatureRenderDispatcher.class,int.class).invoke(pip,ReferenceBodyHost.submitted.get(0),gui,features,1);
                    var blits=new ArrayList<BlitRenderState>();gui.forEachElement(e->{if(e instanceof BlitRenderState b)blits.add(b);},GuiRenderState.TraverseRange.ALL);
                    check(blits.size()==1&&verticesCount>0&&(cpm?exactDefinitionBindings>0:ReferenceBodyHost.ownMaterialAcquisitions>0),"pending warm readiness reaches own genuine BODY/material");
                    PipMatrix.consume(blits.get(0));consumer.getMethod("submitted").invoke(null);
                }
                check(((PreviewRenderer<?>)owner).displayKind()==(cpm?PreviewRenderer.DisplayKind.CPM:PreviewRenderer.DisplayKind.ORDINARY),"pending warm actual BODY ACK remains necessary");
                check(captures==FitCountingObservation.captures&&decodes==FitCountingObservation.decodes,"pending warm transition performs no geometry capture or neutral decode");
                ((AutoCloseable)pip).close();
                System.out.println("PENDING_WARM_PASS "+label+" samePreparation=true nativeMaterialAck=true captures=0 decodes=0");
            }
            numericFixture.close();numericFixture=null;
            type.getMethod("close").invoke(owner);vertices.endFrame();PipCpuBackend.signal();call(CpmBehaviorProbe.class,"drain",new Class<?>[]{},null);
            System.out.println("EARS_PENDING_NATIVE_PASS "+label+" exactPreparation=true referenceAndBodyPasses="+(cached?0:2));
        }finally{custom=false;warm=false;}
    }
    static double number(Map<String,Object> row,String key){return ((Number)row.get(key)).doubleValue();}
    static List<List<Float>> layoutTransforms(Object owner,Class<?> type,String label,PreviewRenderer.PreviewAppearance selected)throws Exception{
        var rows=new ArrayList<List<Float>>();
        for(float[] values:List.of(new float[]{120,150,0.68F,-27},new float[]{330,270,1.7F,34},new float[]{160,180,1,0})){
            ReferenceBodyHost.submitted.clear();
            var request=new PreviewRenderer.PreviewRequest(selected,25,37,(int)values[0],(int)values[1],33,values[3],values[2],PreviewRenderer.PreviewIntent.EDITOR_DRAFT,0,0,854,480);
            type.getMethod("renderGallery",ReferenceBodyHost.Graphics.class,String.class,PreviewRenderer.PreviewRequest.class).invoke(owner,new ReferenceBodyHost.Graphics(),label,request);
            check(ReferenceBodyHost.submitted.size()==1,"current native layout/zoom/pitch request submits one BODY");
            var pip=ReferenceBodyHost.submitted.get(0);var context=ReferenceBodyHost.marker.getMethod("nclskins$previewContext").invoke(pip.renderState());
            check(get(context.getClass(),"capture",context)==null,"prepared layout change never schedules hidden geometry");
            near(pip.scale(),0.97*values[1]/2.125*values[2]*lastFit.fit().normalization(),"numeric fit preserves requested user zoom");
            var original = CenteredPlayerPreviewGeometry.centeredEntityTranslation(CenteredPlayerPreviewGeometry.STANDING_PLAYER_HEIGHT, com.naocraftlab.skins.client.CenteredPipPreviewTransform.pitchRadians(request.pitchDegrees()));
            near(pip.translation().x(),com.naocraftlab.skins.client.PreviewStageGeometry.modelOffsetX(request,pip.scale()),"original yaw pivot and stage X");
            near(pip.translation().y(),original.y()+com.naocraftlab.skins.client.PreviewStageGeometry.modelOffsetY(request,pip.scale()),"original pitch pivot and stage Y");
            near(pip.translation().z(),original.z(),"original pitch placement Z");
            rows.add(List.of(pip.scale(),pip.translation().x(),pip.translation().y(),pip.translation().z(),(float)ReferenceBodyHost.marker.getMethod("nclskins$depthExtent").invoke(pip.renderState())));
        }
        return rows;
    }
    static void near(double actual,double expected,String message){check(Math.abs(actual-expected)<0.001,message+" expected="+expected+" actual="+actual);}
    static void normalized(String label,com.naocraftlab.skins.probe.EarsJoinBridge.Processed input,Map<String,Object> measured)throws Exception{
        ReferenceNormalization.factor=Math.min(1,number(reference,"height")/number(measured,"height"));ReferenceNormalization.center=number(measured,"centerY");ReferenceNormalization.referenceCenter=number(reference,"centerY");ReferenceNormalization.source=input.id().toString();ReferenceNormalization.enabled=true;
        try{
            var result=draw(label+"/normalized-current",input,true,31,"");near(number(result,"displayHeight"),number(measured,"displayHeight")*ReferenceNormalization.factor,"actual full PIP native mesh consumes normalization exactly once");near(number(result,"displayCenterY"),90+(number(measured,"displayCenterY")-90)*ReferenceNormalization.factor,"actual full PIP native mesh preserves original authored placement");check(number(result,"displayHeight")<=number(reference,"displayHeight")+0.001,"height-only full native output limited by reference");
            if(ReferenceNormalization.factor<0.99){ReferenceNormalization.sensitivity="omit-factor";var omitted=draw(label+"/sensitivity-omit-factor",input,true,31,"");check(number(omitted,"displayHeight")>number(reference,"displayHeight")+0.1,"omitted numeric factor fails actual native height cap");ReferenceNormalization.sensitivity="recenter";var wrong=draw(label+"/sensitivity-reference-center",input,true,31,"");check(Math.abs(number(wrong,"displayCenterY")-number(result,"displayCenterY"))>0.1,"recenter fails authored native placement");}
        }finally{ReferenceNormalization.enabled=false;ReferenceNormalization.sensitivity="";}
    }
    static FeatureRenderDispatcher features;
    static void execute(String[] args,PipProcessState process)throws Exception{
        restart=System.getProperty("nclskins.numeric.restartManifest")!=null;
        if(restart){
            var saved=com.google.gson.JsonParser.parseString(Files.readString(Path.of(System.getProperty("nclskins.numeric.restartManifest")))).getAsJsonObject();
            saved.getAsJsonObject("numericRoots").entrySet().forEach(e->numericRoots.put(e.getKey(),e.getValue().getAsString()));
            originalOutput=Path.of(saved.get("fixtureOutput").getAsString());warm=true;
        }
        EarsOptionalAbsenceProbe.execute();
        var out=Path.of(args[0]);Files.createDirectories(out);CpmBehaviorProbe.initializeHost(out.resolve("synthetic-config.json").toFile());initialNetwork=network();CpmIntegration.initialize((c,n,m)->{throw new AssertionError("new resource/network demand forbidden");});host();
        var level=(net.minecraft.client.renderer.LevelRenderer)allocate(net.minecraft.client.renderer.LevelRenderer.class);set(net.minecraft.client.renderer.LevelRenderer.class,"targets",level,allocate(net.minecraft.client.renderer.LevelTargetBundle.class));set(net.minecraft.client.Minecraft.class,"levelRenderer",minecraft,level);
        var previous=com.tom.cpm.shared.MinecraftClientAccess.get();var mc=new MinecraftObject();manager=mc.getPlayerRenderManager();ClientBase.mc=mc;com.tom.cpm.shared.MinecraftObjectHolder.setClientObject(previous);
        var clientClass=Class.forName("com.tom.cpm.client.CustomPlayerModelsClient");Object client=clientClass.getField("INSTANCE").get(null);if(client==null){client=clientClass.getConstructor().newInstance();clientClass.getField("INSTANCE").set(null,client);}ClientBase.class.getField("manager").set(client,new com.tom.cpm.shared.model.RenderManager(manager,null,java.util.function.Function.identity()));
        if(System.getProperty("nclskins.upstream.family").equals("neoforge"))process.installNeoEvents(client);
        com.tom.cpm.shared.MinecraftObjectHolder.setClientObject((com.tom.cpm.shared.MinecraftClientAccess)java.lang.reflect.Proxy.newProxyInstance(ReferenceBodyProbe.class.getClassLoader(),new Class<?>[]{com.tom.cpm.shared.MinecraftClientAccess.class},(p,m,a)->m.getName().equals("createTexture")?mc.createTexture():m.invoke(previous,a)));
        try(var bootstrap=GalleryNativePassFixture.numeric(new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle("synthetic:numeric-bootstrap",64,64),SkinModel.CLASSIC,Optional.empty(),PreviewRenderer.CapeMode.OFF,true),Files.readAllBytes(Path.of(System.getProperty("nclskins.reference.ears"))),Files.createTempDirectory("numeric-bootstrap-"))){}
        try(var registration=CapeProjection.installEvents((CapeProjection.Events)call(CpmBehaviorProbe.class,"events",new Class<?>[]{},null));var profiles=CpmBehaviorProbe.nativeProfileScope();var input=com.naocraftlab.skins.probe.EarsJoinBridge.process(Path.of(System.getProperty("nclskins.reference.ears")))){
            var earsHost=get(com.naocraftlab.skins.probe.EarsProbe.Host.class,"INSTANCE",null);var nativeTextures=get(com.naocraftlab.skins.probe.EarsProbe.Host.class,"textures",earsHost);set(TextureManager.class,"byPath",textures,get(nativeTextures.getClass(),"map",nativeTextures));
            avatar=new Avatar(rendererContext());check(avatar.layerCount()==11,"genuine all eleven constructor-installed layers");rawRenderer();
            var buffers=(RenderBuffers)allocate(RenderBuffers.class);set(RenderBuffers.class,"stagedVertexBuffer",buffers,vertices);features=new FeatureRenderDispatcher(buffers,null,null,null,new net.minecraft.client.renderer.state.GameRenderState());
            definitions=(ModelDefinitionLoader<UUID>)get(CpmBehaviorProbe.class,"loader",null);
            for(String resource:List.of("white.png","template/cape.png"))try(var image=ReferenceBodyProbe.class.getClassLoader().getResourceAsStream("assets/cpm/textures/"+resource)){check(image!=null,"public pinned CPM material");textures.register(net.minecraft.resources.Identifier.parse("cpm:textures/"+resource),new DynamicTexture(()->"public CPM material",com.mojang.blaze3d.platform.NativeImage.read(image)));}
            Path referencePath=out.resolve("reference-ordinary.png");Files.write(referencePath,(byte[])call(com.naocraftlab.skins.probe.EarsProbe.class,"png",new Class<?>[]{int.class,int.class},null,64,64));try(var ordinary=com.naocraftlab.skins.probe.PipNativeInputs.process(referencePath)){reference=draw("ordinary/reference-classic",ordinary,true,31,"");slim=true;var slimReference=draw("ordinary/reference-slim",ordinary,true,31,"");near(number(slimReference,"height"),number(reference,"height"),"actual classic/slim native reference height equal");check(!slimReference.get("geometrySha256").equals(reference.get("geometrySha256")),"genuine slim constructor emits distinct arm geometry");slim=false;}
            Path rejectionInput = out.resolve("ears-rejection-source.png");
            Files.copy(Path.of(System.getProperty("nclskins.reference.ears")), rejectionInput);
            if(!restart) rejectedCpmFit(rejectionInput, com.naocraftlab.skins.core.png.CpmCarrierReader.Status.UNKNOWN);
            if(!restart) rejectedCpmFit(rejectionInput, com.naocraftlab.skins.core.png.CpmCarrierReader.Status.MALFORMED);
            draw("ears-body",input,true,31,"");
            slim=true;draw("ears-body-slim",input,true,31,"");slim=false;
            try(var combined=com.naocraftlab.skins.probe.PipNativeInputs.process(Path.of(System.getProperty("nclskins.reference.combined")))){
                check(combined.texture().earsFeatures().enabled,"same combined carrier genuinely processed by Ears");
                custom=true;
                draw("combined-cpm-ears",combined,true,31,"");custom=false;
                if (!restart) ReferenceScreenDemand.execute(combined);
            }
            try(var cpmOnly=com.naocraftlab.skins.probe.PipNativeInputs.process(Path.of(System.getProperty("nclskins.pip.changedCarrier")))){
                custom=true;draw("cpm-tall-translated-jump",cpmOnly,true,31,"");
                accessory=PreviewRenderer.CapeMode.CAPE;draw("cpm-cape-positive",cpmOnly,true,31,"");check(ReferenceVertices.counts.getOrDefault("CPM_CAPE",0)==24&&!ReferenceVertices.counts.containsKey("FALLBACK_CAPE"),"actual native CPM CAPE positive 24 vertices without fallback");
                accessory=PreviewRenderer.CapeMode.ELYTRA;draw("cpm-elytra-positive",cpmOnly,true,31,"");check(ReferenceVertices.counts.getOrDefault("CPM_ELYTRA",0)==48&&!ReferenceVertices.counts.containsKey("FALLBACK_ELYTRA"),"actual native CPM ELYTRA positive 48 vertices without fallback");accessory=PreviewRenderer.CapeMode.OFF;custom=false;
                if(!restart) ReferenceScreenDemand.providers(cpmOnly);
            }
            set(CpmNeutralGeometryProbe.class,"config",null,get(CpmBehaviorProbe.class,"config",null));set(CpmNeutralGeometryProbe.class,"tags",null,get(CpmBehaviorProbe.class,"tags",null));
            var smallImage=(com.tom.cpl.util.Image)call(CpmCurrentNativeGeometryProbe.class,"fixture",new Class<?>[]{boolean.class,boolean.class,boolean.class,boolean.class,float.class,float.class},null,false,false,false,true,0.5F,0F);
            var raster=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_ARGB);raster.setRGB(0,0,64,64,smallImage.getData(),0,64);Path smallPath=out.resolve("small.png");javax.imageio.ImageIO.write(raster,"PNG",smallPath.toFile());
            try(var small=com.naocraftlab.skins.probe.PipNativeInputs.process(restart?originalOutput.resolve("small.png"):smallPath)){custom=true;draw("cpm-small",small,true,31,"");custom=false;}
            if(!restart) ReferenceScreenDemand.retained(Path.of(System.getProperty("nclskins.reference.combined")),smallPath,Path.of(System.getProperty("nclskins.pip.changedCarrier")));
            if(!restart){
                com.naocraftlab.skins.probe.EarsMeasurementChecks.execute(Path.of(System.getProperty("nclskins.reference.ears")),"ears-only");
                com.naocraftlab.skins.probe.EarsMeasurementChecks.execute(Path.of(System.getProperty("nclskins.reference.combined")),"combined");
                pendingFit(Path.of(System.getProperty("nclskins.reference.ears")),false,false);
                pendingFit(Path.of(System.getProperty("nclskins.reference.combined")),true,false);
                pendingFit(Path.of(System.getProperty("nclskins.reference.ears")),false,true);
                pendingFit(Path.of(System.getProperty("nclskins.reference.combined")),true,true);
            }
            features.close();vertices.close();GalleryNativePassFixture.closeNumericCoordinator();
        }
    }
    public static void main(String[] args)throws Exception{
        try(var state=new PipProcessState()){execute(args,state);}check(initialNetwork.equals(network()),"zero world/network/event changes before="+initialNetwork+" after="+network());Files.writeString(Path.of(args[0]).resolve("reference-body.json"),new com.google.gson.Gson().toJson(Map.of("cases",cases,"caseCount",cases.size(),"warmCases",warmCases,"warmCaseCount",warmCases.size(),"numericRoots",numericRoots,"fixtureOutput",Path.of(args[0]).toAbsolutePath().toString(),"f0Complete",false,"nativeDataBindingsRestored",true,"networkCounters",network())));System.out.println("REFERENCE_BODY_RESULT cases="+cases.size()+" warmCases="+warmCases.size()+" f0Complete=false");
    }
}
