package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.compat.cpm.*;
import com.naocraftlab.skins.client.CpmModelFileConversion.*;
import com.naocraftlab.skins.client.SkinModel;
import com.tom.cpm.shared.editor.*;
import com.tom.cpm.shared.io.*;
import com.tom.cpm.shared.model.*;
import com.tom.cpm.shared.parts.*;
import com.tom.cpm.shared.definition.*;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpl.util.Image;
import com.google.gson.Gson;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class CpmProjectCompositionProbe {
    private static Path output,fixtures,oracle;
    private static String mode;
    private static final List<Map<String,Object>> cases=new ArrayList<>();
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    private static final class SyntheticPlayer extends com.tom.cpm.shared.config.Player<Object> {
        public SkinType getSkinType(){return SkinType.SLIM;}
        protected com.tom.cpm.shared.skin.PlayerTextureLoader initTextures(){throw new AssertionError("forbidden profile fetch");}
        public String getName(){return "Synthetic";}
        public UUID getUUID(){return UUID.fromString("00000000-0000-0000-0000-000000000123");}
        public void updateFromPlayer(com.tom.cpm.shared.animation.AnimationState a,Object b){throw new AssertionError();}
        public Object getGameProfile(){throw new AssertionError("forbidden profile read");}
        public void updateFromModel(com.tom.cpm.shared.animation.AnimationState a,Object b){throw new AssertionError();}
        @Override public boolean isClientPlayer(){return true;}
    }
    private static Editor editor(SkinType type,String name)throws Exception{
        var field=CpmProjectBindingProbe.class.getDeclaredField("fixtures");field.setAccessible(true);field.set(null,fixtures);
        var method=CpmProjectBindingProbe.class.getDeclaredMethod("editor",SkinType.class,String.class);method.setAccessible(true);
        if(!"template".equals(name))return (Editor)method.invoke(null,type,name);
        var editor=(Editor)method.invoke(null,type,null);var project=new com.tom.cpm.shared.editor.project.ProjectFile();
        project.load(compositionFixtures().resolve("template.cpmproject").toFile()).join();
        CpmProjectImportScope.run(editor,17,dependencies(),()->{CpmProjectImportScope.loadProject(editor,17,project);return null;});
        var template=editor.templates.get(0);template.applyToModel();var cube=template.getCubes().get(0).getCube();
        check(cube.rgb==0xff51a2e3&&cube.u==18&&cube.v==24&&cube.texSize==2,"upstream ProjectIO template retains actual nondefault color/UV args");
        check(template.getTemplateTexture().getImage().getRGB(63,62)==0xff1234ab,"upstream template retains actual far-pixel author texture");return editor;
    }
    private static Path compositionFixtures(){return Path.of(System.getProperty("nclskins.cpm.projectFixtures",fixtures.resolveSibling("project-composition").toString()));}
    private static Map<String,byte[]> dependencies()throws IOException{return Map.of("p:synthetic",Files.readAllBytes(compositionFixtures().resolve("template.json")));}
    private static byte[] compiled(Editor e)throws IOException{
        var d=CpmProjectImportScope.compile(e,17);var b=new ByteArrayOutputStream();b.write(83);
        try(var c=new ChecksumOutputStream(b)){var h=new IOHelper(c);h.writeObjectBlock(new ModelPartSkinType(e.skinType));d.writeBlocks(h);h.writeObjectBlock(ModelPartEnd.END);}return b.toByteArray();
    }
    private static byte[] envelope(byte[] payload)throws IOException{
        var b=new ByteArrayOutputStream();b.write(83);try(var c=new ChecksumOutputStream(b)){var h=new IOHelper(c);h.writeUTF("Synthetic");h.writeUTF("");h.writeByteArray(payload);h.writeByteArray(new byte[0]);h.writeVarInt(0);}return b.toByteArray();
    }
    private static byte[] read(Raster raster,int count)throws IOException{return new SkinDataInputStream(new Image(raster.argb(),64),MinecraftClientAccess.get().getDefinitionLoader().getTemplate(),raster.variant()==SkinModel.SLIM?SkinType.SLIM.getChannel():SkinType.DEFAULT.getChannel()).readNBytes(count);}
    private interface Case{void run()throws Exception;}
    private static void test(String name,Case action){
        try{action.run();cases.add(Map.of("case",name,"status","PASS"));}
        catch(Throwable t){cases.add(Map.of("case",name,"status",t instanceof AssertionError||t instanceof Failure?"FAIL":"UNKNOWN","type",t.getClass().getName(),"reason",String.valueOf(t.getMessage())));t.printStackTrace();}
        System.out.println(new Gson().toJson(cases.get(cases.size()-1)));
    }
    private static Raster base(SkinType type){var image=type.getSkinTexture();image=new Image(image);image.setRGB(8,8,0x00123456);return new Raster(type==SkinType.SLIM?SkinModel.SLIM:SkinModel.CLASSIC,image.getData());}
    private static Raster canonical(Raster raster)throws Exception{
        var image=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,64,64,raster.argb(),0,64);var bytes=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
        var normalized=new com.naocraftlab.skins.core.png.PngValidator().projectImport(bytes.toByteArray());var decoded=javax.imageio.ImageIO.read(new ByteArrayInputStream(normalized.pngBytes()));int[] pixels=decoded.getRGB(0,0,64,64,null,0,64);
        check(Arrays.equals(raster.argb(),pixels),"canonical PNG before actual CPM reader preserves all RGBA");return new Raster(raster.variant(),pixels);
    }
    public static void verifyRawPayload(byte[] payload, Path inputs, Path expected, String name) throws Exception {
        fixtures = inputs; oracle = expected; semantics(payload, name);
    }
    public static void verifyPublishedCarrier(byte[] png, byte[] wire, Path inputs, SkinType type) throws Exception {
        fixtures = inputs;
        var decoded = javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
        var image = new Image(decoded.getRGB(0,0,64,64,null,0,64),64);
        var stream = new SkinDataInputStream(image, MinecraftClientAccess.get().getDefinitionLoader().getTemplate(), type.getChannel());
        check(stream.read() == 83, "Published saved carrier uses native header and selected channel");
        var checksum = new ChecksumInputStream(stream);
        var reader = new IOHelper(checksum);
        IModelPart link = null;
        int types = 0, links = 0;
        while (true) {
            var part = reader.readObjectBlock(ModelPartType.VALUES, (kind, block) -> kind.getFactory().create(block,
                    new ModelDefinition(MinecraftClientAccess.get().getDefinitionLoader(), new SyntheticPlayer())));
            if (part == null) continue;
            if (part instanceof ModelPartEnd) break;
            if (part instanceof ModelPartSkinType skinType) {
                types++;
                check(skinType.getSkinType() == type, "Confirmed link retains concrete native type");
            } else {
                check(part instanceof ModelPartLink && part.getType() == ModelPartType.DEFINITION_LINK, "Published carrier holds definition link, not package/container/inline substitute");
                check(((ModelPartLink) part).getLink().toString().equals("p:synthetic"), "Native writer uses the terminal-confirmed Paste id, never source URL");
                link = part; links++;
            }
        }
        checksum.checkSum();
        check(types == 1 && links == 1, "Published native carrier has exactly one type and confirmed link");
        var hosted = CpmImportFeasibilityProbe.class.getDeclaredField("hosted"); hosted.setAccessible(true);
        var binding = CpmImportFeasibilityProbe.class.getDeclaredField("bindingSetup"); binding.setAccessible(true);
        var loads = CpmImportFeasibilityProbe.class.getDeclaredField("fakeLoads"); loads.setAccessible(true);
        Object oldHosted = hosted.get(null); boolean oldBinding = binding.getBoolean(null); int before = loads.getInt(null);
        hosted.set(null, wire.clone()); binding.setBoolean(null, false);
        try {
            var resolved = link.resolve();
            check(resolved instanceof ModelPartDefinition, "Actual native link reader consumes uploaded definition wire");
            definitionSemantics((ModelPartDefinition) resolved, new byte[0], "dense-atlas");
            check(loads.getInt(null) == before + 1, "One exact p:synthetic resolver request reaches only fake hosted terminal");
        } finally {
            hosted.set(null, oldHosted); binding.setBoolean(null, oldBinding);
        }
    }
    private static void semantics(byte[] payload,String name)throws Exception{
        var checksum=new ChecksumInputStream(new ByteArrayInputStream(payload,1,payload.length-1));var reader=new IOHelper(checksum);
        ModelPartDefinition definition=null;
        while(true){var p=reader.readObjectBlock(ModelPartType.VALUES,(t,r)->t.getFactory().create(r,null));if(p==null)continue;if(p instanceof ModelPartEnd)break;if(p instanceof ModelPartDefinition d)definition=d;}
        checksum.checkSum();check(definition!=null,"actual reader retains default definition kind");
        definitionSemantics(definition, payload, name);
    }
    private static void definitionSemantics(ModelPartDefinition definition, byte[] payload, String name) throws Exception {
        var written=new IOHelper();definition.write(written);var r=written.flip();int count=r.readVarInt();
        check(count==(name.equals("template")?0:1),"independent authored geometry count");
        for(int i=0;i<count;i++){var cube=Cube.loadDefinitionCube(r);check(cube.size.x==3&&cube.size.y==5&&cube.size.z==7&&cube.pos.x==2&&cube.pos.y==4&&cube.pos.z==6,"independent authored geometry dimensions and position");}
        boolean texture=false,animation=false,template=false;
        while(true){var p=r.readObjectBlock(ModelPartType.VALUES,(t,h)->t.getFactory().create(h,null));if(p==null)continue;if(p instanceof ModelPartEnd)break;
            if(p.getType()==ModelPartType.SKIN){texture=true;var w=new IOHelper();p.write(w);var imageReader=w.flip();imageReader.read2s();var block=imageReader.readImage();block.doReadImage();var image=block.getImage();int width=name.contains("atlas")?128:64;
                check(image.getWidth()==width&&image.getHeight()==width,"full model atlas dimensions remain distinct from vanilla fallback");
                try(var zip=new java.util.zip.ZipFile(fixtures.resolve(name+".cpmproject").toFile());var png=zip.getInputStream(zip.getEntry("skin.png"))){var expected=javax.imageio.ImageIO.read(png);check(Arrays.equals(image.getData(),expected.getRGB(0,0,width,width,null,0,width)),"every authored atlas pixel including far corners retained");}
            }
            if(p.getType()==ModelPartType.ANIMATION_DATA)animation=true;
            if(p.getType()==ModelPartType.TEMPLATE){template=true;var w=new IOHelper();p.write(w);var h=w.flip();check(new Link(h).toString().equals("p:synthetic"),"template dependency identity/kind retained, not flattened");var a=h.readNextBlock();for(int value:new int[]{0x51,0xa2,0xe3,2,3,5})check(a.read()==value,"independent serialized template color/UV arguments");}
        }
        check(texture==(!name.equals("borrowed")&&!name.equals("template")),"authored versus borrowed texture semantics");
        check(animation==name.equals("animation"),"custom animation block retained");check(template==name.equals("template"),"template block retained");
        if(name.equals("animation"))check(Arrays.equals(payload,Files.readAllBytes(oracle.resolve("animation.payload"))),"independent original default animation/keyframe bytes");
        if(name.equals("template"))check(Arrays.equals(payload,Files.readAllBytes(compositionFixtures().resolve("template.payload"))),"independently retained upstream template ProjectIO/compile bytes");
    }
    private static void baseline(String name,SkinType type)throws Exception{
        var e=editor(type,name);byte[] payload=CpmProjectImportScope.run(e,17,Map.of(),()->compiled(e));
        if(name.equals("canonical"))check(Arrays.equals(payload,Files.readAllBytes(oracle.resolve("default-"+type+".payload"))),"independent default compiler setup");
        check(e.elements.stream().anyMatch(r->!r.children.isEmpty())||name.equals("template")&&e.templates.size()==1,"original actual ProjectIO producer geometry setup");
        var adapter=new CpmModelFileAdapter();
        if(mode.equals("baseline"))try(var admitted=adapter.admit(new ByteArrayInputStream(envelope(payload)))){
            var result=((Inline)adapter.prepare(admitted,base(type),17)).raster();check(Arrays.equals(payload,read(result,payload.length)),"actual project compiler to adapter to carrier full bytes");
        }
        else {
            var prepared=adapter.prepareProject(e,17,Map.of(),base(type));
            check(Arrays.equals(payload,prepared.compiled()),"independent retained compiler payload before carrier");
            Raster result;
            if(prepared.inline().isPresent()) {
                result=canonical(prepared.inline().orElseThrow());
                check(Arrays.equals(payload,read(result,payload.length)),"actual project compiler to adapter to carrier full bytes");
            } else {
                check(name.equals("dense-atlas")&&payload.length>30720,"publication only on genuine dense actual overflow");
                var delivered=Base64.getDecoder().decode(Base64.getEncoder().encode(prepared.wire()));
                if(mode.equals("container-as-payload"))delivered=envelope(prepared.wire());
                if(mode.equals("wrong-kind"))delivered=Files.readAllBytes(fixtures.resolve("modern.wire"));
                result=canonical(adapter.linkProject(prepared,new Link("p:synthetic")));
                var stream=new SkinDataInputStream(new Image(result.argb(),64),MinecraftClientAccess.get().getDefinitionLoader().getTemplate(),type.getChannel());
                check(stream.read()==83,"linked carrier header");var checksum=new ChecksumInputStream(stream);var reader=new IOHelper(checksum);
                IModelPart link=null;
                while(true){var part=reader.readObjectBlock(ModelPartType.VALUES,(t,r)->t.getFactory().create(r,new ModelDefinition(MinecraftClientAccess.get().getDefinitionLoader(),new SyntheticPlayer())));if(part==null)continue;if(part instanceof ModelPartEnd)break;if(part instanceof ModelPartSkinType st)check(st.getSkinType()==type,"linked writer preserves exact concrete variant, never raw-retags");if(part instanceof ModelPartLink)link=part;}
                checksum.checkSum();check(link!=null&&link.getType()==ModelPartType.DEFINITION_LINK,"actual definition link kind not package or source URL");
                var loads=CpmImportFeasibilityProbe.class.getDeclaredField("fakeLoads");loads.setAccessible(true);int beforeLoads=loads.getInt(null);
                var hosted=CpmImportFeasibilityProbe.class.getDeclaredField("hosted");hosted.setAccessible(true);hosted.set(null,delivered);
                var binding=CpmImportFeasibilityProbe.class.getDeclaredField("bindingSetup");binding.setAccessible(true);binding.setBoolean(null,false);
                try {
                    com.tom.cpm.shared.parts.IResolvedModelPart resolved;
                    try{resolved=link.resolve();}catch(Exception failure){throw new AssertionError("actual definition reader rejects wrong-kind/container publication payload",failure);}
                    check(resolved instanceof ModelPartDefinition,"actual link reader resolves actual definition payload");
                    var body=new IOHelper();((ModelPartDefinition)resolved).write(body);var expected=new IOHelper(new ByteArrayInputStream(payload,1,payload.length-1));
                    expected.readObjectBlock(ModelPartType.VALUES,(t,r)->t.getFactory().create(r,null));
                    var original=expected.readObjectBlock(ModelPartType.VALUES,(t,r)->t.getFactory().create(r,null));var bytes=new IOHelper();original.write(bytes);
                    check(Arrays.equals(bytes.toBytes(),body.toBytes()),"complete dense geometry and full atlas after real linked resolution");
                } finally {hosted.set(null,null);binding.setBoolean(null,true);}
                check(loads.getInt(null)==beforeLoads+1,"one actual CPM reader request reaches bounded fake terminal");
                Files.writeString(output.resolve("transport.json"),new Gson().toJson(Map.of("terminal_calls",1,"wire_kind","DEFINITION_LINK","compiled_bytes",prepared.compiled().length,"wire_bytes",delivered.length,"wire_sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(delivered)),"real_network",false,"remote_offline_guarantee",false)));
            }
            semantics(prepared.compiled(),name);
            check(result.argb()[8+8*64]==0x00123456,"significant transparent RGB outside carrier");
            Files.write(output.resolve(name+"-"+type+".payload"),prepared.compiled());Files.write(output.resolve(name+"-"+type+".wire"),prepared.wire());
            var image=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,64,64,result.argb(),0,64);
            var png=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",png);
            var normalized=new com.naocraftlab.skins.core.png.PngValidator().projectImport(png.toByteArray());
            var decoded=javax.imageio.ImageIO.read(new ByteArrayInputStream(normalized.pngBytes()));check(Arrays.equals(result.argb(),decoded.getRGB(0,0,64,64,null,0,64)),"canonical PNG normalization preserves all carrier pixels");
            Files.write(output.resolve(name+"-"+type+".png"),normalized.pngBytes());
        }
    }
    private static Editor capacityEditor(SkinType type,int count,int seed)throws Exception{
        var editor=editor(type,"canonical");var texture=editor.textures.get(TextureSheetType.SKIN);var image=new Image(texture.getImage());var random=new Random(seed);
        for(int i=0;i<count;i++)image.setRGB(i%64,i/64,random.nextInt()|0xff000000);texture.setImage(image);texture.setEdited(true);return editor;
    }
    private static void controls()throws Exception{
        for(var type:SkinType.VANILLA_TYPES)test("actual-compiler-capacity-inclusive-plus1-"+type,()->{
            int capacity=0;var probe=new SkinDataOutputStream(new Image(64,64),MinecraftClientAccess.get().getDefinitionLoader().getTemplate(),type.getChannel());
            try{while(capacity<65536){probe.write(0);capacity++;}}catch(EOFException end){}check(capacity>2048&&capacity<65536,"finite actual initialized public writer capacity");
            for(int extra=0;extra<=1;extra++){
                int target=capacity+extra;Editor found=null;
                for(int seed=1;seed<=16&&found==null;seed++){
                    int low=0,high=4096;
                    while(low<=high){int n=(low+high)/2;var e=capacityEditor(type,n,seed);byte[] p=CpmProjectImportScope.run(e,17,Map.of(),()->compiled(e));
                        if(p.length==target){found=e;break;}if(p.length<target)low=n+1;else high=n-1;
                    }
                }
                check(found!=null,"actual upstream compiler fixture reaches exact capacity boundary "+target);
                var prepared=new CpmModelFileAdapter().prepareProject(found,17,Map.of(),base(type));check(prepared.compiled().length==target,"exact actual compiler boundary, not container size");
                check(prepared.inline().isPresent()==(extra==0),"inclusive fits and plus1 requires publication without partial success");
                if(extra==0)check(Arrays.equals(prepared.compiled(),read(prepared.inline().orElseThrow(),target)),"inclusive exact carrier readback");
                var project=new com.tom.cpm.shared.editor.project.ProjectFile();com.tom.cpm.shared.editor.project.ProjectIO.saveProject(found,project);project.save(output.resolve("boundary-"+type+"-"+extra+".cpmproject").toFile()).join();
                Files.write(output.resolve("boundary-"+type+"-"+extra+".payload"),prepared.compiled());
            }
            Files.writeString(output.resolve("capacity-"+type+".txt"),Integer.toString(capacity));
        });
        test("actual-project-inline-fit-beyond-2048",()->{
            var source=editor(SkinType.SLIM,"canonical");var skin=source.textures.get(TextureSheetType.SKIN);var image=new Image(skin.getImage());var random=new Random(15973);
            for(int i=0;i<650;i++)image.setRGB(i%64,i/64,random.nextInt()|0xff000000);skin.setImage(image);skin.setEdited(true);int[] expected=image.getData().clone();
            var project=new com.tom.cpm.shared.editor.project.ProjectFile();com.tom.cpm.shared.editor.project.ProjectIO.saveProject(source,project);var path=output.resolve("upstream-large-inline.cpmproject");project.save(path.toFile()).join();
            var persisted=new com.tom.cpm.shared.editor.project.ProjectFile();persisted.load(path.toFile()).join();var e=editor(SkinType.SLIM,null);CpmProjectImportScope.run(e,17,Map.of(),()->{CpmProjectImportScope.loadProject(e,17,persisted);return null;});
            var p=new CpmModelFileAdapter().prepareProject(e,17,Map.of(),base(SkinType.SLIM));check(p.compiled().length>2048,"actual default compiler output beyond historical blanket cutoff");
            var carrier=p.inline().orElseThrow();check(Arrays.equals(p.compiled(),read(carrier,p.compiled().length)),"real actual-fit preparation is inline without publication");
            var h=new IOHelper(new ByteArrayInputStream(read(carrier,p.compiled().length),1,p.compiled().length-1));h.readObjectBlock(ModelPartType.VALUES,(t,r)->t.getFactory().create(r,null));var d=h.readObjectBlock(ModelPartType.VALUES,(t,r)->t.getFactory().create(r,null));var b=new IOHelper();d.write(b);var r=b.flip();int n=r.readVarInt();for(int i=0;i<n;i++)Cube.loadDefinitionCube(r);boolean found=false;
            while(true){var part=r.readObjectBlock(ModelPartType.VALUES,(t,in)->t.getFactory().create(in,null));if(part==null)continue;if(part instanceof ModelPartEnd)break;if(part.getType()==ModelPartType.SKIN){var w=new IOHelper();part.write(w);var in=w.flip();in.read2s();var block=in.readImage();block.doReadImage();check(Arrays.equals(expected,block.getImage().getData()),"independent seeded complete texture after real producer/native adapter/carrier/reader");found=true;}}
            check(found,"actual-fit texture is not dropped");Files.write(output.resolve("large-inline.payload"),p.compiled());
        });
        test("missing-base-and-variant-before-compiler",()->{
            var adapter=new CpmModelFileAdapter();var e=editor(SkinType.SLIM,"canonical");boolean denied=false;
            try{adapter.prepareProject(e,17,Map.of(),null);}catch(CpmProjectImportScope.MissingData ex){denied=true;}check(denied,"missing base typed before compiler");
            denied=false;try{adapter.prepareProject(e,17,Map.of(),base(SkinType.DEFAULT));}catch(CpmProjectImportScope.UnsupportedData ex){denied=true;}check(denied,"concrete type cannot be silently retagged");
            check(!CpmProjectImportScope.owns(e),"no retained operation frame");
        });
        test("project-uuid-missing-identity-and-explicit-compiler-control",()->{
            Class.forName("net.minecraft.client.Minecraft").getField("current").set(null,null);
            var adapter=new CpmModelFileAdapter();var e=editor(SkinType.SLIM,"uuid-lock");check(e.description.uuid==null,"actual persisted V1 project never stores synthetic author target");boolean denied=false;
            try{adapter.prepareProject(e,17,Map.of(),base(SkinType.SLIM));}catch(CpmProjectImportScope.MissingData ex){denied=true;}check(denied,"missing current identity typed, no hidden current-player loading");
            e.description.uuid=UUID.fromString("00000000-0000-0000-0000-000000000123");var prepared=adapter.prepareProject(e,17,Map.of(),base(SkinType.SLIM));var carrier=prepared.inline().orElseThrow();
            check(Arrays.equals(prepared.compiled(),read(carrier,prepared.compiled().length)),"compiled locked bytes stay exact through carrier");
            var source=new IOHelper(new ByteArrayInputStream(prepared.compiled(),1,prepared.compiled().length-1));source.readObjectBlock(ModelPartType.VALUES,(t,r)->t.getFactory().create(r,null));
            var definition=source.readObjectBlock(ModelPartType.VALUES,(t,r)->t.getFactory().create(r,null));var data=new IOHelper();definition.write(data);var r=data.flip();int n=r.readVarInt();for(int i=0;i<n;i++)Cube.loadDefinitionCube(r);boolean found=false;
            while(true){var p=r.readObjectBlock(ModelPartType.VALUES,(t,h)->t.getFactory().create(h,null));if(p==null)continue;if(p instanceof ModelPartEnd)break;if(p instanceof ModelPartUUIDLockout){var w=new IOHelper();p.write(w);check(w.flip().readUUID().equals(UUID.fromString("00000000-0000-0000-0000-000000000123")),"independent original lock UUID retained without apply or bypass");found=true;}}
            check(found,"compiled lock not dropped");
        });
        test("project-version-and-missing-template",()->{
            var e=editor(SkinType.SLIM,null);var p=new com.tom.cpm.shared.editor.project.ProjectFile();p.load(fixtures.resolve("future.cpmproject").toFile()).join();boolean denied=false;
            try{CpmProjectImportScope.run(e,17,Map.of(),()->{CpmProjectImportScope.loadProject(e,17,p);return null;});}catch(CpmProjectImportScope.UnsupportedData ex){denied=true;}check(denied,"future version typed before ProjectIO loader/compiler");
            var t=new com.tom.cpm.shared.editor.project.ProjectFile();t.load(compositionFixtures().resolve("template.cpmproject").toFile()).join();denied=false;
            try{CpmProjectImportScope.run(e,17,Map.of(),()->{CpmProjectImportScope.loadProject(e,17,t);return null;});}catch(CpmProjectImportScope.DependencyRequired ex){denied=ex.link().equals("p:synthetic");}check(denied,"missing required template requests exact link without global resource fallback");
        });
        test("unknown-raw-data-preserved-without-lossy-reserialize",()->{
            var adapter=new CpmModelFileAdapter();byte[] locked=Files.readAllBytes(fixtures.resolve("classic.payload"));
            try(var admitted=adapter.admit(new ByteArrayInputStream(envelope(locked)))){var raster=canonical(((Inline)adapter.prepare(admitted,base(SkinType.DEFAULT),17)).raster());check(Arrays.equals(locked,read(raster,locked.length)),"independent precompiled UUID lock bytes remain exact through existing adapter and PNG");}
            var bytes=new ByteArrayOutputStream();bytes.write(83);try(var checksum=new ChecksumOutputStream(bytes)){var h=new IOHelper(checksum);h.writeObjectBlock(new ModelPartSkinType(SkinType.DEFAULT));h.writeByte(250);h.writeVarInt(3);h.writeByte(0x19);h.writeByte(0x51);h.writeByte(0xa3);h.writeObjectBlock(ModelPartEnd.END);}
            try(var admitted=adapter.admit(new ByteArrayInputStream(envelope(bytes.toByteArray())))) {
                var result=canonical(((Inline)adapter.prepare(admitted,base(SkinType.DEFAULT),17)).raster());
                check(Arrays.equals(bytes.toByteArray(),read(result,bytes.size())),"unknown raw parts survive native conversion byte-for-byte");
            }
        });
        test("prepared-ownership-and-public-link-priority",()->{
            var a=new CpmModelFileAdapter();var e=editor(SkinType.SLIM,"dense-atlas");var base=base(SkinType.SLIM);int[] original=base.argb();var p=a.prepareProject(e,17,Map.of(),base);
            byte[] bytes=p.compiled();bytes[0]=0;check(p.compiled()[0]==83,"prepared bytes defensive immutable");check(Arrays.equals(original,base.argb()),"original fallback pixels unchanged");
            boolean denied=false;try{new CpmModelFileAdapter().linkProject(p,new Link("p:synthetic"));}catch(CpmProjectImportScope.UnsupportedData ex){denied=true;}check(denied,"foreign prepared owner denied");
            for(String value:List.of("local:synthetic","unknown:synthetic")){denied=false;try{a.linkProject(p,new Link(value));}catch(CpmProjectImportScope.UnsupportedData ex){denied=true;}check(denied,"nonportable or unsupported link is not publication success");}
            for(String kind:List.of("p","gh","git","ms","pb")){
                var expected=new Link(kind+":synthetic");var result=canonical(a.linkProject(p,expected));check(result.variant()==SkinModel.SLIM,"compatible existing public link does not require new publication or retag");
                var in=new SkinDataInputStream(new Image(result.argb(),64),MinecraftClientAccess.get().getDefinitionLoader().getTemplate(),SkinType.SLIM.getChannel());check(in.read()==83,"existing-link PNG header");var checksum=new ChecksumInputStream(in);var h=new IOHelper(checksum);h.readObjectBlock(ModelPartType.VALUES,(t,r)->t.getFactory().create(r,null));
                var part=h.readObjectBlock(ModelPartType.VALUES,(t,r)->t.getFactory().create(r,null));check(part instanceof ModelPartDefinitionLink&&((ModelPartLink)part).getLink().equals(expected),"existing supported link identity and definition kind preserved without resolving or publishing");check(h.readObjectBlock(ModelPartType.VALUES,(t,r)->t.getFactory().create(r,null)) instanceof ModelPartEnd,"existing link exact end");checksum.checkSum();
            }
            var inline=a.prepareProject(editor(SkinType.SLIM,"canonical"),17,Map.of(),base);denied=false;try{a.linkProject(inline,new Link("p:synthetic"));}catch(CpmProjectImportScope.UnsupportedData ex){denied=true;}check(denied,"actual inline fit wins over unnecessary publication");
        });
    }
    private static void rawResources()throws Exception{
        var parent=Thread.currentThread().getContextClassLoader();var original=new java.net.URLClassLoader(new java.net.URL[]{Path.of(System.getProperty("nclskins.cpm.original")).toUri().toURL()},null);
        Thread.currentThread().setContextClassLoader(new ClassLoader(parent){@Override public InputStream getResourceAsStream(String name){if(name.equals("com/tom/cpm/shared/editor/Exporter.class")||name.equals("com/tom/cpm/shared/editor/template/EditorTemplate.class"))return original.getResourceAsStream(name);return super.getResourceAsStream(name);}});
    }
    public static void main(String[] args)throws Exception{
        fixtures=Path.of(args[0]);output=Path.of(args[1]);mode=args[2];oracle=Path.of(args[3]);Files.createDirectories(output);
        CpmImportFeasibilityProbe.main(new String[]{args[0],args[1],"project-binding-setup"});rawResources();
        for(var type:SkinType.VANILLA_TYPES)test("canonical-"+type,()->baseline("canonical",type));
        test("atlas-native-composition",()->baseline("atlas",SkinType.SLIM));
        test("dense-native-composition",()->baseline("dense-atlas",SkinType.SLIM));
        if(!mode.equals("baseline")){
            for(String name:List.of("borrowed","animation","template"))test(name+"-native-composition",()->baseline(name,SkinType.SLIM));
            controls();
            test("zero-forbidden-native-effects",()->{var f=CpmImportFeasibilityProbe.class.getDeclaredField("effects");f.setAccessible(true);check(((Map<?,?>)f.get(null)).isEmpty(),"no popup/profile/global load/cache/config/assignment/payload logging including DEBUGGING");});
            test("foreign-public-default-unchanged",()->{
                var f=CpmProjectBindingProbe.class.getDeclaredField("output");f.setAccessible(true);f.set(null,output);
                var method=CpmProjectBindingProbe.class.getDeclaredMethod("publicExport",Editor.class,String.class);method.setAccessible(true);
                byte[] value=(byte[])method.invoke(null,editor(SkinType.SLIM,"canonical"),"foreign-public-default");
                check(Arrays.equals(value,Files.readAllBytes(oracle.resolve("default-SLIM.payload"))),"ordinary foreign upstream public writer remains exact and healthy");
            });
            var effects=CpmImportFeasibilityProbe.class.getDeclaredField("effects");effects.setAccessible(true);Files.writeString(output.resolve("effects.json"),new Gson().toJson(effects.get(null)));
        }
        Files.writeString(output.resolve("composition-cases.json"),new Gson().toJson(cases));
        check(cases.stream().allMatch(c->c.get("status").equals("PASS")),"native composition cases not all PASS");
    }
}
