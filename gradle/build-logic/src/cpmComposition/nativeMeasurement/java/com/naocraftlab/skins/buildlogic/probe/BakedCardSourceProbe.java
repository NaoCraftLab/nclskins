package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.runtime.*;
import com.naocraftlab.skins.probe.EarsJoinBridge;
import java.nio.file.*;
import java.util.*;
import static com.naocraftlab.skins.buildlogic.probe.PipMaterialProbe.check;

final class BakedCardSourceProbe {
    static void retired(BakedCardConsumer.Baked value) throws Exception {
        try {value.nativeConsumer.consume();throw new AssertionError("retired native target must reject acquisition");}
        catch(IllegalStateException expected){check(expected.getMessage().equals("Dispatcher is closed") && expected.getSuppressed().length==0,"actual closed-dispatcher lifetime guard, no hidden cleanup failure");}
    }
    static void transitions(GalleryNativePassFixture subject) throws Exception {
            try(var host=new BakedCardConsumer(subject.runtime)) {
                var a=BakedSurfacePresenterFixture.transition(subject,0);
                var b=BakedSurfacePresenterFixture.transition(subject,1);
                var c=BakedSurfacePresenterFixture.transition(subject,2);
                host.sync(a.view());subject.drainWorkers();host.textures.settle();host.sync(a.view());
                var ownA=host.draw(a.view()).get(0);host.consume(ownA,"resource-A",a.preview());
                host.draw(a.view());
                host.sync(b.view());
                check(host.draw(b.view()).isEmpty(),"held B actual loader cannot borrow A material");
                subject.drainWorkers();host.textures.settle();host.sync(b.view());
                var ownB=host.draw(b.view()).get(0);
                host.sync(c.view());subject.drainWorkers();host.textures.settle();host.sync(c.view());
                var ownC=host.draw(c.view()).get(0);var row=host.consume(ownC,"resource-C",c.preview());
                retired(ownA);retired(ownB);
                check(ownA.nativeConsumer.primaryDraws==1 && ownA.nativeConsumer.materialPasses==1 && ownA.nativeConsumer.completions==1
                        && ownB.nativeConsumer.primaryDraws==0 && ownB.nativeConsumer.materialPasses==0 && ownB.nativeConsumer.completions==0,"actual retired A/B native queued targets cannot draw or acknowledge C");
                row.put("heldBeforeRegistration",true);row.put("staleARejected",true);row.put("staleBRejected",true);
                Files.writeString(Path.of(System.getProperty("probe.dir"),"baked-card-source-transition.json"),new com.google.gson.Gson().toJson(row));
            }
    }

    static void sameId(GalleryNativePassFixture subject) throws Exception {
        var card=BakedSurfacePresenterFixture.transition(subject,2);
        com.naocraftlab.skins.probe.CardTextureBridge.holdNextIo();
        java.util.concurrent.CompletableFuture<?> held;
        try(var old=new BakedCardConsumer(subject.runtime)) {
            old.sync(card.view());subject.drainWorkers();held=com.naocraftlab.skins.probe.CardTextureBridge.awaitHeldIo();
            check(old.draw(card.view()).isEmpty(),"real native I/O pending is not ready material");
        }
        try(var replacement=new BakedCardConsumer(subject.runtime)) {
            replacement.sync(card.view());subject.drainWorkers();replacement.textures.settle();replacement.sync(card.view());
            var own=replacement.draw(card.view()).get(0);var object=replacement.textures.registered(own.request.appearance().skin().location());
            var row=replacement.consume(own,"resource-same-ID",card.preview());
            com.naocraftlab.skins.probe.CardTextureBridge.releaseHeldIo(held);
            check(replacement.textures.registered(own.request.appearance().skin().location())==object,"same-ID late I/O preserves exact replacement native texture");
            row.put("heldBeforeMainEnqueue",true);row.put("fifoPreserved",true);row.put("exactReplacementRetained",true);
            Files.writeString(Path.of(System.getProperty("probe.dir"),"baked-card-same-id.json"),new com.google.gson.Gson().toJson(row));
        } finally {com.naocraftlab.skins.probe.CardTextureBridge.releaseHeldIo(held);}
    }

    static void execute(EarsJoinBridge.Processed input) throws Exception {
        var content=List.of(input.content(),Files.readAllBytes(Path.of(System.getProperty("nclskins.reference.combined"))),Files.readAllBytes(Path.of(System.getProperty("nclskins.reference.ears"))));
        var root=Files.createTempDirectory("card-source-cache-");
        var importRoot=Files.createDirectories(root.resolve("skinshuffle"));
        var records=new ArrayList<Map<String,Object>>();
        for(int i=0;i<content.size();i++) {
            var file=importRoot.resolve("owned"+i+".png");Files.write(file,content.get(i));
            records.add(Map.of("name","Owned "+i,"skin",Map.of("type","file","path",file.toString(),"model","classic")));
        }
        Files.writeString(importRoot.resolve("presets.json"),new com.google.gson.Gson().toJson(Map.of("loadedPresets",records)));
        var appearance=new PreviewRenderer.PreviewAppearance(new TextureRegistry.TextureHandle(input.id().toString(),64,64),SkinModel.CLASSIC,Optional.empty(),PreviewRenderer.CapeMode.OFF,OuterLayerVisibility.allVisible());
        try(var subject=GalleryNativePassFixture.cards(appearance,input.content(),root.resolve("library"),BakedSurfacePresenterFixture.catalog(content))) {
            var measured=ReferenceBodyProbe.lastFit;
            var numeric=new com.naocraftlab.skins.runtime.infrastructure.PreviewModelFitStorageAdapter(new com.naocraftlab.skins.core.storage.NclSkinsStorage(root.resolve("library"),new com.naocraftlab.skins.core.png.PngValidator(),java.time.Clock.systemUTC()));
            String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(new com.naocraftlab.skins.core.png.PngValidator().normalizeSkin(input.content())));
            var numericKey=new PreviewModelFitStore.Key(subject.sessionAccount,hash,com.naocraftlab.skins.core.model.SkinVariant.CLASSIC);
            var numericValue=new PreviewModelFitStore.Value(measured.referenceHeight(),measured.referenceCenterX(),measured.referenceCenterY(),measured.referenceCenterZ(),measured.fit());
            check(numeric.create(numericKey,numericValue,()->true).status()==PreviewModelFitStore.Status.PRESENT && numeric.load(numericKey).value().equals(numericValue) && numericValue.fit().normalization()<0.5,"actual measured matching FULL record in card runtime storage scope");
            var layouts=subject.cardLayoutRoundTrip();
            Files.writeString(Path.of(System.getProperty("probe.dir"),"baked-card-layout-continuity.json"),new com.google.gson.Gson().toJson(layouts));
            var cards=BakedSurfacePresenterFixture.joined(subject,importRoot);
            var groups=new LinkedHashMap<ViewSpec,List<BakedSurfacePresenterFixture.Case>>();
            for(var card:cards)groups.computeIfAbsent(card.view(),ignored->new ArrayList<>()).add(card);
            var ledger=new ArrayList<Map<String,Object>>();
            int captures=FitCountingObservation.captures,classifiers=FitCountingObservation.classifiers,copies=FitCountingObservation.copies;
            int outside=FitCountingObservation.classifiersOutsideAssetIo,decodes=FitCountingObservation.decodes;
            var noEffects=subject.noEffectsCheckpoint();
            transitions(subject);
            sameId(subject);
            for(var group:groups.entrySet())try(var host=new BakedCardConsumer(subject.runtime)) {
                host.sync(group.getKey());subject.drainWorkers();host.sync(group.getKey());subject.drainWorkers();host.textures.settle();host.sync(group.getKey());
                var nativeCards=host.draw(group.getKey());
                check(nativeCards.size()==group.getValue().size(),"every actual loaded card reaches compiled static paint: "+group.getValue().get(0).role()+" "+nativeCards.size()+"/"+group.getValue().size());
                for(int i=0;i<nativeCards.size();i++) {
                    var card=group.getValue().get(i);BakedSurfacePresenterFixture.verify(card);
                    var loaded=subject.runtime.loadSkinPreview(card.preview());subject.drainWorkers();
                    var request=nativeCards.get(i).request;
                    check(Arrays.equals(loaded.join().orElseThrow(),host.textures.content.get(request.appearance().skin().location())),"actual source bytes survive cache registration and compiled host relookup "+card.role());
                    if(card.preview().catalogImage().filter(image->!image.collectionId().equals(PersonalSkinCatalog.COLLECTION_ID)).isPresent()) {
                        int index=Integer.parseInt(card.preview().catalogImage().orElseThrow().skinId().substring(1));
                        check(Arrays.equals(new com.naocraftlab.skins.core.png.PngValidator().normalizeSkin(content.get(index)),loaded.join().orElseThrow()),"distinct raw catalog producer retained");
                    }
                    var row=host.consume(nativeCards.get(i),card.role(),card.preview());
                    row.put("producer",card.preview().catalogImage().map(image->"catalog:"+image.collectionId()+":"+image.skinId()).orElseGet(()->card.preview().externalImage().map(image->"candidate:"+image.candidateId()).orElse("library:"+card.preview().skin().assetId())));
                    ledger.add(row);
                }
            }
            check(ledger.stream().map(row->row.get("role")).distinct().count()==6 && ledger.stream().map(row->row.get("source")).distinct().count()>=3,"six source-bound owners and distinguishable A/B/C registrations");
            check(subject.numericLoads==0 && subject.numericCreates==0 && captures==FitCountingObservation.captures && outside==FitCountingObservation.classifiersOutsideAssetIo && copies==FitCountingObservation.copies && decodes==FitCountingObservation.decodes,"static cards perform no full numeric/model work");noEffects.run();
            check(numeric.load(numericKey).value().equals(numericValue),"cards never mutate matching FULL winner");
            for(var row:ledger){row.put("assetIoClassifiers",FitCountingObservation.classifiers-classifiers);row.put("fullClassifiers",0);row.put("captures",0);row.put("numericLoads",0);row.put("storedFullFactor",numericValue.fit().normalization());}
            Files.writeString(Path.of(System.getProperty("probe.dir"),"baked-card-source-cache.json"),new com.google.gson.Gson().toJson(ledger));
            System.out.println("BAKED_CARD_SOURCE_CACHE_PASS cases="+ledger.size()+" owners=6");
        }
    }
}
