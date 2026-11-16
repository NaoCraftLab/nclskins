package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.runtime.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static com.naocraftlab.skins.buildlogic.probe.PipMaterialProbe.check;

public final class BakedMenuColdProbe {
    public static void main(String[] args) throws Exception {
        try(var device=new PipProcessState()) {
            PipMaterialProbe.host();
            execute(args);
            PipMaterialProbe.vertices.close();
        }
    }
    private static void execute(String[] args) throws Exception {
        var manifest=com.google.gson.JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        var selected=manifest.getAsJsonArray("cases").asList().stream().map(com.google.gson.JsonElement::getAsJsonObject)
                .filter(row->row.get("case").getAsString().equals("cpm-tall-translated-jump")).findFirst().orElseThrow();
        String contentKey=selected.get("contentKey").getAsString();
        var root=Path.of(manifest.getAsJsonObject("numericRoots").get(contentKey).getAsString());
        var key=new PreviewModelFitStore.Key(new UUID(0,801),contentKey.split("/")[0],com.naocraftlab.skins.core.model.SkinVariant.CLASSIC);
        var stored=new com.naocraftlab.skins.runtime.infrastructure.PreviewModelFitStorageAdapter(new com.naocraftlab.skins.core.storage.NclSkinsStorage(root,new com.naocraftlab.skins.core.png.PngValidator(),java.time.Clock.systemUTC())).load(key);
        check(stored.status()==PreviewModelFitStore.Status.PRESENT && stored.value().fit().normalization()<0.5,"fresh child reads earlier genuine durable FULL winner");
        var operations=(ClientOperations)java.lang.reflect.Proxy.newProxyInstance(BakedMenuColdProbe.class.getClassLoader(),new Class<?>[]{ClientOperations.class},(proxy,method,values)-> {
            if(method.getName().equals("close") || method.getName().equals("onCapeObservation") || method.getName().equals("closeOptiFineCapes"))return null;
            if(method.getName().equals("sessionIdentity"))return new GameSessionTokenSource.SessionIdentity(new UUID(0,801),"Fixture");
            throw new AssertionError("fresh menu may not invoke operations: "+method.getName());
        });
        var store=new PreviewModelFitStore() {
            public Result load(Key key){throw new AssertionError("fresh menu numeric demand");}
            public Result create(Key key,Value value,java.util.function.BooleanSupplier current){throw new AssertionError("fresh menu numeric write");}
        };
        var bytes=Files.readAllBytes(Path.of(args[1]));
        var rows=new ArrayList<Map<String,Object>>();
        try(var textures=new com.naocraftlab.skins.probe.CardTextureBridge()) {
            String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            check(hash.equals(contentKey.split("/")[0]),"menu source matches genuine persisted FULL identity");
            var handle=textures.register(TextureRegistry.TextureKind.PLAYER_SKIN,hash,bytes);textures.settle();
            try(var runtime=BakedSurfacePresenterFixture.coldMenu(operations,store,handle)) {
                for(boolean pause:List.of(false,true))try(var baked=new CpmBakedDisplayProbe.SelectedFallback()) {
                    check(runtime.snapshot().lifecycle()==ClientSnapshot.Lifecycle.NEW,"fresh menu runtime never initialized/opened");
                    textures.registered(handle.location());baked.menu(runtime,pause);baked.consume();
                    check(baked.primaryDraws==1 && baked.materialPasses==1 && handle.location().equals(baked.source),"fresh actual title/pause material and completed native draw");
                    check(Math.abs(baked.scale-0.97*110/2.125*0.92)<0.001,"fresh menu baked baseline ignores durable FULL");
                    rows.add(Map.of("role",pause?"pause":"title","source",baked.source,"scale",baked.scale,"fullFactor",stored.value().fit().normalization(),"drawTerminals",baked.primaryDraws,"materials",baked.materialPasses,"runtime","NEW","galleryEver",false));
                }
            }
        }
        Files.writeString(Path.of(System.getProperty("probe.dir"),"baked-menu-cold.json"),new com.google.gson.Gson().toJson(rows));
        check(FitCountingObservation.captures==0 && FitCountingObservation.copies==0 && FitCountingObservation.decodes==0,"fresh menu has zero neutral/full model work");
        System.out.println("BAKED_MENU_COLD_PASS title=true pause=true GalleryEver=false");
    }
}
