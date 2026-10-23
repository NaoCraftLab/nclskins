package com.naocraftlab.skins.buildlogic.probe;

import com.tom.cpm.shared.*;
import com.tom.cpm.shared.editor.*;
import com.tom.cpm.shared.editor.project.*;
import com.tom.cpm.shared.editor.elements.*;
import com.tom.cpm.shared.editor.util.ModelDescription;
import com.tom.cpm.shared.model.*;
import com.tom.cpm.shared.io.*;
import com.tom.cpl.util.Image;
import com.tom.cpl.math.Vec2i;
import com.naocraftlab.skins.compat.cpm.CpmProjectImportScope;
import com.naocraftlab.skins.compat.cpm.CpmProjectBindings;
import com.tom.cpm.shared.editor.template.*;
import com.tom.cpm.shared.parts.*;
import com.google.gson.Gson;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.io.*;

public final class CpmProjectBindingProbe {
    private static Path output, fixtures;
    private static <T> T host(Class<T> c) throws Exception {
        var m=CpmImportFeasibilityProbe.class.getDeclaredMethod("host",Class.class);m.setAccessible(true);return c.cast(m.invoke(null,c));
    }
    private static void flag(String name, boolean value) throws Exception {
        var f=CpmImportFeasibilityProbe.class.getDeclaredField(name);f.setAccessible(true);f.setBoolean(null,value);
    }
    private static void check(boolean b,String s) { if(!b)throw new AssertionError(s); }
    private static Editor editor(SkinType type,String name) throws Exception {
        var e=new Editor();e.setUI(host(com.tom.cpl.gui.UI.class));e.skinType=type;
        for(var p:PlayerModelParts.VALUES)if(p!=PlayerModelParts.CUSTOM_PART)e.elements.add(new ModelElement(e,ElementType.ROOT_PART,p));
        e.vanillaSkin=type.getSkinTexture();var skin=e.textures.get(TextureSheetType.SKIN);skin.setDefaultImg(e.vanillaSkin);skin.setImage(new Image(e.vanillaSkin));skin.provider.size=new Vec2i(64,64);skin.setEdited(false);
        if(name!=null){var p=new ProjectFile();p.load(fixtures.resolve(name+".cpmproject").toFile()).join();ProjectIO.loadProject(e,p);e.skinType=type;}
        return e;
    }
    private static byte[] publicExport(Editor e,String name) throws Exception {
        var desc=new ModelDescription();desc.name="Synthetic";desc.desc="";var p=output.resolve(name+".cpmmodel");
        flag("authoring",true);try{Exporter.exportModel(e,e.ui,p.toFile(),desc,false);}finally{flag("authoring",false);}
        check(Files.exists(p),"healthy public default export must produce file");
        try(var in=Files.newInputStream(p)){return ModelFile.load(in).getDataBlock();}
    }
    private static final List<Map<String,Object>> cases=new ArrayList<>();
    private interface Case {void run() throws Exception;}
    private static void test(String name,Case action) {
        try{action.run();cases.add(Map.of("case",name,"status","PASS"));}
        catch(Throwable failure){cases.add(Map.of("case",name,"status",failure instanceof AssertionError?"FAIL":"UNKNOWN","type",failure.getClass().getName(),"reason",String.valueOf(failure.getMessage())));failure.printStackTrace();}
        System.out.println(new Gson().toJson(cases.get(cases.size()-1)));
    }
    @SuppressWarnings("unchecked") private static Map<String,Integer> effects() throws Exception {
        var f=CpmImportFeasibilityProbe.class.getDeclaredField("effects");f.setAccessible(true);return (Map<String,Integer>)f.get(null);
    }
    private static byte[] compiled(Editor e) throws IOException {
        var definition=CpmProjectImportScope.compile(e,17);check(definition!=null,"valid project compiler must return definition");var b=new ByteArrayOutputStream();b.write(83);
        try(var checksum=new ChecksumOutputStream(b)){var h=new IOHelper(checksum);h.writeObjectBlock(new ModelPartSkinType(e.skinType));definition.writeBlocks(h);h.writeObjectBlock(ModelPartEnd.END);}
        return b.toByteArray();
    }
    private static List<IModelPart> parts(byte[] b) throws IOException {
        var result=new ArrayList<IModelPart>();var in=new ChecksumInputStream(new ByteArrayInputStream(b,1,b.length-1));var h=new IOHelper(in);
        while(true){var p=h.readObjectBlock(ModelPartType.VALUES,(t,r)->t.getFactory().create(r,null));if(p==null)continue;if(p instanceof ModelPartEnd)break;result.add(p);}in.checkSum();return result;
    }
    private static void rawResources() throws Exception {
        var prior=Thread.currentThread().getContextClassLoader();var originals=new URLClassLoader(new URL[]{Path.of(System.getProperty("nclskins.cpm.original")).toUri().toURL()},null);
        Thread.currentThread().setContextClassLoader(new ClassLoader(prior){
            @Override public InputStream getResourceAsStream(String name){if(name.equals("com/tom/cpm/shared/editor/Exporter.class")||name.equals("com/tom/cpm/shared/editor/template/EditorTemplate.class"))return originals.getResourceAsStream(name);return super.getResourceAsStream(name);}
        });
    }
    private static void green(Path oracle) throws Exception {
        rawResources();
        for(var type:SkinType.VANILLA_TYPES)test("default-equivalence-"+type,()->{
            var e=editor(type,"canonical");byte[] b=CpmProjectImportScope.run(e,17,Map.of(),()->compiled(e));
            check(Arrays.equals(b,Files.readAllBytes(oracle.resolve("default-"+type+".payload"))),"independently frozen actual public default bytes");check(!CpmProjectImportScope.owns(e),"finally cleanup");
            Files.write(output.resolve("compiled-"+type+".payload"),b);
        });
        for(String name:List.of("atlas","dense-atlas","borrowed","animation"))test(name,()->{
            var e=editor(SkinType.SLIM,name);byte[] b=CpmProjectImportScope.run(e,17,Map.of(),()->compiled(e));var definition=parts(b).stream().filter(ModelPartDefinition.class::isInstance).map(ModelPartDefinition.class::cast).findFirst().orElseThrow();
            var h=new IOHelper();definition.write(h);var rd=h.flip();int count=rd.readVarInt();check(count==1,"authored cube count");var cube=Cube.loadDefinitionCube(rd);check(cube.size.x==3&&cube.size.y==5&&cube.size.z==7&&cube.pos.x==2,"authored geometry");
            boolean texture=false,animation=false;while(true){var p=rd.readObjectBlock(ModelPartType.VALUES,(t,r)->t.getFactory().create(r,null));if(p==null)continue;if(p instanceof ModelPartEnd)break;
                if(p.getType()==ModelPartType.SKIN){texture=true;var w=new IOHelper();p.write(w);var r=w.flip();r.read2s();var ib=r.readImage();ib.doReadImage();var image=ib.getImage();int width=name.contains("atlas")?128:64;check(image.getWidth()==width&&image.getHeight()==width,"full atlas dimensions");
                    try(var zip=new java.util.zip.ZipFile(fixtures.resolve(name+".cpmproject").toFile());var png=zip.getInputStream(zip.getEntry("skin.png"))){var expected=javax.imageio.ImageIO.read(png);check(Arrays.equals(image.getData(),expected.getRGB(0,0,width,width,null,0,width)),"independent complete atlas pixels including far corners");}}
                if(p.getType()==ModelPartType.ANIMATION_DATA)animation=true;
            }
            if(name.equals("dense-atlas"))check(b.length>30720,"dense compiled output beyond UI30KiB cap");
            if(name.equals("borrowed"))check(!texture,"borrowed/default texture compiler semantics");else check(texture,"authored texture retained");
            if(name.equals("animation")){check(animation&&e.animations.get(0).getFrames().size()==1&&e.animations.get(0).duration==1000,"custom animation retained");check(Arrays.equals(b,Files.readAllBytes(oracle.resolve("animation.payload"))),"independent actual default export preserves exact animation/keyframes");}
            Files.write(output.resolve(name+".payload"),b);
        });
        test("uuid-missing-current-identity",()->{Class.forName("net.minecraft.client.Minecraft").getField("current").set(null,null);var e=editor(SkinType.SLIM,"uuid-lock");check(e.description.uuid==null,"ProjectIO V1 does not persist synthetic uuid");boolean denied=false;try{CpmProjectImportScope.run(e,17,Map.of(),()->compiled(e));}catch(CpmProjectImportScope.MissingData ex){denied=true;}check(denied,"missing current identity is typed before compiler/player loading");check(!CpmProjectImportScope.owns(e),"failed scope cleanup");});
        test("uuid-explicit-synthetic-compiler-only",()->{var e=editor(SkinType.SLIM,"uuid-lock");e.description.uuid=UUID.fromString("00000000-0000-0000-0000-000000000123");var b=CpmProjectImportScope.run(e,17,Map.of(),()->compiled(e));check(b.length>0,"synthetic explicit target compiler control only");});
        test("scope-reentrancy-exception-thread-finally",()->{
            var e=editor(SkinType.SLIM,"canonical");CpmProjectImportScope.run(e,17,Map.of(),()->{
                check(CpmProjectImportScope.owns(e),"scope active");boolean denied=false;try{CpmProjectImportScope.run(e,17,Map.of(),()->null);}catch(IOException ex){denied=true;}check(denied,"own reentrancy denied");
                var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();var other=new Thread(()->{try{check(!CpmProjectImportScope.owns(e),"foreign thread isolated");}catch(Throwable ex){failure.set(ex);}});other.start();try{other.join();}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new InterruptedIOException();}check(failure.get()==null,"foreign thread assertion propagated");return null;
            });check(!CpmProjectImportScope.owns(e),"normal finally removed");
            try{CpmProjectImportScope.run(e,17,Map.of(),()->{throw new IOException("CONTROL");});}catch(IOException expected){}check(!CpmProjectImportScope.owns(e),"exception finally removed");
            Thread.currentThread().interrupt();try{CpmProjectImportScope.run(e,17,Map.of(),()->{throw new AssertionError("interrupted entered");});}catch(InterruptedIOException expected){}finally{check(Thread.interrupted(),"interrupt retained");}check(!CpmProjectImportScope.owns(e),"interrupted cleanup");
        });
        test("scope-reader-retirement",()->{
            var e=editor(SkinType.SLIM,null);var reader=new InputStream[1];byte[] dep={11,22,33};
            CpmProjectImportScope.run(e,17,Map.of("p:synthetic",dep),()->{dep[0]=99;reader[0]=CpmProjectImportScope.template(e,new com.tom.cpm.shared.definition.Link("p:synthetic"),com.tom.cpm.shared.config.ResourceLoader.ResourceEncoding.NO_ENCODING,null);check(reader[0].read()==11,"defensive immutable bytes");return null;});
            check(reader[0].read()==-1,"operation reader cannot be consumed after finally retirement");
        });
        test("foreign-default-equivalence-and-log",()->{
            var owned=editor(SkinType.SLIM,null);var foreign=editor(SkinType.SLIM,"canonical");int before=effects().getOrDefault("ILogger.info",0);
            CpmProjectImportScope.run(owned,17,Map.of(),()->{try{check(Arrays.equals(publicExport(foreign,"foreign-independent"),Files.readAllBytes(oracle.resolve("default-SLIM.payload"))),"foreign original default bytes unchanged");}catch(IOException ex){throw ex;}catch(Exception ex){throw new IOException(ex);}return null;});
            check(effects().getOrDefault("ILogger.info",0)==before+(MinecraftObjectHolder.DEBUGGING?1:0),"foreign log original exactly once");
        });
        test("debug-owned-zero-foreign-once",()->{
            var e=editor(SkinType.SLIM,"canonical");int before=effects().getOrDefault("ILogger.info",0);CpmProjectImportScope.run(e,17,Map.of(),()->compiled(e));check(effects().getOrDefault("ILogger.info",0)==before,"owned logger zero before toString");
            var foreign=editor(SkinType.SLIM,"canonical");CpmProjectImportScope.run(e,17,Map.of(),()->{try{publicExport(foreign,"foreign-default");}catch(IOException ex){throw ex;}catch(Exception ex){throw new IOException(ex);}return null;});
            check(effects().getOrDefault("ILogger.info",0)==before+(MinecraftObjectHolder.DEBUGGING?1:0),"foreign original logger delegates once");
        });
        test("upstream-template-args-texture-project-roundtrip",CpmProjectBindingProbe::template);
        test("foreign-template-delegates-once",()->{
            byte[] dep=Files.readAllBytes(output.resolve("authored-template.json"));var owned=editor(SkinType.SLIM,null);var foreign=editor(SkinType.SLIM,null);
            var response=CpmImportFeasibilityProbe.class.getDeclaredField("hosted");response.setAccessible(true);var count=CpmImportFeasibilityProbe.class.getDeclaredField("fakeLoads");count.setAccessible(true);int before=count.getInt(null);
            response.set(null,dep);try{CpmProjectImportScope.run(owned,17,Map.of(),()->{check(EditorTemplate.load(foreign,new JsonMapImpl(new HashMap<>(Map.of("link","p:synthetic","data",new HashMap<>())))).getCubes().size()==1,"foreign original valid template result");return null;});}finally{response.set(null,null);}
            check(count.getInt(null)==before+1,"foreign original loader exactly once");
        });
        test("project-version-and-stale-revision",()->{
            var e=editor(SkinType.SLIM,null);var future=new ProjectFile();future.load(fixtures.resolve("future.cpmproject").toFile()).join();boolean denied=false;
            try{CpmProjectImportScope.run(e,17,Map.of(),()->{CpmProjectImportScope.loadProject(e,17,future);return null;});}catch(CpmProjectImportScope.UnsupportedData ex){denied=true;}check(denied,"unknown actual ProjectIO registry version typed unsupported before load");
            denied=false;try{CpmProjectImportScope.run(e,17,Map.of(),()->CpmProjectImportScope.compile(e,18));}catch(CpmProjectImportScope.MissingData ex){denied=true;}check(denied,"stale revision denied before compiler");
            var valid=new ProjectFile();valid.load(fixtures.resolve("canonical.cpmproject").toFile()).join();CpmProjectImportScope.run(e,17,Map.of(),()->{CpmProjectImportScope.loadProject(e,17,valid);compiled(e);return null;});
        });
        test("invalid-upstream-data-typed-cleanup",()->{
            var e=editor(SkinType.SLIM,null);boolean typed=false;try{CpmProjectImportScope.run(e,17,Map.of("p:synthetic",new byte[]{123}),()->EditorTemplate.load(e,new JsonMapImpl(new HashMap<>(Map.of("link","p:synthetic","data",new HashMap<>())))));}catch(IOException ex){typed=true;}catch(RuntimeException ex){}
            check(typed,"malformed actual template produces typed IOException, not raw parser exception");check(!CpmProjectImportScope.owns(e),"malformed factory finally cleanup");
        });
        Files.writeString(output.resolve("binding-cases.json"),new Gson().toJson(cases));
        check(cases.stream().allMatch(c->c.get("status").equals("PASS")),"reference dimensions not all PASS");
    }
    private static void template() throws Exception {
        var author=editor(SkinType.SLIM,"canonical");author.templateSettings=new TemplateSettings(author);
        var arg=new TemplateArgHandler(author,"Tint","Synthetic",TemplateArgType.COLOR);
        var color=(com.tom.cpm.shared.editor.template.args.ColorEditorArg)arg.handler;color.loadProject(new HashMap<>(Map.of("name","Tint","color","ff12ab34")));
        arg.effectedElems.add(author.elements.stream().flatMap(r->r.children.stream()).findFirst().orElseThrow());author.templateSettings.templateArgs.add(arg);
        var desc=new ModelDescription();desc.name="Synthetic";desc.desc="";var text=new String[1];TemplateExporter.exportTemplate(author,author.ui,desc,v->text[0]=v);check(text[0]!=null,"actual upstream TemplateExporter fixture producer");
        byte[] dependency=text[0].getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(output.resolve("authored-template.json"),dependency);
        var e=editor(SkinType.SLIM,null);var data=new HashMap<String,Object>();data.put("Tint",new HashMap<>(Map.of("value","ff51a2e3")));data.put(com.tom.cpm.shared.editor.template.args.TexEditorArg.NAME,new HashMap<>(Map.of("u",3,"v",5,"ts",2)));
        var json=new JsonMapImpl(new HashMap<>(Map.of("link","p:synthetic","data",data)));
        try{CpmProjectImportScope.run(e,17,Map.of("p:synthetic",dependency),()->{e.templates.add(EditorTemplate.load(e,json));return null;});}
        catch(Throwable failure){throw new AssertionError("owned prepared template must bypass global loader",failure);}
        var t=e.templates.get(0);t.applyToModel();var cube=t.getCubes().get(0).getCube();check(cube.rgb==0xff51a2e3,"actual nondefault color arg");check(cube.u==18&&cube.v==24&&cube.texSize==2,"nontrivial exact UV texture args");check(t.getTemplateTexture()!=null&&t.getTemplateTexture().getImage().getRGB(63,62)==0xff1234ab,"actual retained author template far-pixel texture");
        check(t.getTemplateDefaultTexture()==e.textures.get(TextureSheetType.SKIN).provider,"merged UV mode uses the actual editor texture, not author texture substitution");
        CpmProjectImportScope.run(e,17,Map.of("p:synthetic",dependency),()->{var defaultTemplate=EditorTemplate.load(e,new JsonMapImpl(new HashMap<>(Map.of("link","p:synthetic","data",new HashMap<>()))));check(defaultTemplate.getTemplateDefaultTexture().getImage().getRGB(63,62)==0xff1234ab,"unmerged default template texture semantics");return null;});
        var p=new ProjectFile();ProjectIO.saveProject(e,p);var file=output.resolve("upstream-template.cpmproject");p.save(file.toFile()).join();var loaded=new ProjectFile();loaded.load(file.toFile()).join();
        var re=editor(SkinType.SLIM,null);byte[] exported=CpmProjectImportScope.run(re,17,Map.of("p:synthetic",dependency),()->{CpmProjectImportScope.loadProject(re,17,loaded);return compiled(re);});check(re.templates.size()==1,"actual ProjectIO template save/load");
        var definition=parts(exported).stream().filter(ModelPartDefinition.class::isInstance).findFirst().orElseThrow();var written=new IOHelper();definition.write(written);var rd=written.flip();int cubes=rd.readVarInt();for(int i=0;i<cubes;i++)Cube.loadDefinitionCube(rd);boolean found=false;
        while(true){var part=rd.readObjectBlock(ModelPartType.VALUES,(type,reader)->type.getFactory().create(reader,null));if(part==null)continue;if(part instanceof ModelPartEnd)break;if(part.getType()==ModelPartType.TEMPLATE){var h=new IOHelper();part.write(h);var r=h.flip();check(new com.tom.cpm.shared.definition.Link(r).toString().equals("p:synthetic"),"serialized link kind and identity");var args=r.readNextBlock();for(int value:new int[]{0x51,0xa2,0xe3,2,3,5})check(args.read()==value,"independent serialized color and UV arguments");found=true;}}
        check(found,"default compiler serializes real template args without flattening");Files.write(output.resolve("template.payload"),exported);
        boolean denied=false;try{CpmProjectImportScope.run(e,17,Map.of(),()->EditorTemplate.load(e,json));}catch(CpmProjectImportScope.DependencyRequired ex){denied=ex.link().equals("p:synthetic");}check(denied,"missing dependency requests exact link without global fallback");
        denied=false;try{CpmProjectImportScope.run(e,17,Map.of("p:other",dependency),()->EditorTemplate.load(e,json));}catch(CpmProjectImportScope.DependencyRequired ex){denied=ex.link().equals("p:synthetic");}check(denied,"mismatched dependency requests exact missing link without global fallback");
    }
    public static void main(String[] args) throws Exception {
        fixtures=Path.of(args[0]);output=Path.of(args[1]);Files.createDirectories(output);
        CpmImportFeasibilityProbe.main(new String[]{args[0],args[1],args[2].startsWith("green")?"project-binding-setup":"setup"});
        if(args[2].startsWith("green")){green(Path.of(args[3]));return;}
        if(args[2].equals("untransformed")) {
            var e=editor(SkinType.SLIM,"canonical");Throwable failure=null;try{CpmProjectImportScope.run(e,17,Map.of(),()->compiled(e));}catch(Throwable t){failure=t;}
            check(failure instanceof CpmProjectImportScope.UnsupportedData,"untransformed compiler must be typed unavailable before invoker, got "+failure);return;
        }
        if(args[2].equals("oracle-animation")) {
            Files.write(output.resolve("animation.payload"),publicExport(editor(SkinType.SLIM,"animation"),"animation-default"));return;
        }
        if(args[2].equals("oracle")) {
            for(var type:SkinType.VANILLA_TYPES){var e=editor(type,"canonical");var b=publicExport(e,"default-"+type);Files.write(output.resolve("default-"+type+".payload"),b);System.out.println("DEFAULT_ORACLE "+type+" "+b.length);}
        } else if(args[2].equals("log-red")) {
            var e=editor(SkinType.SLIM,"canonical");
            check(e.elements.stream().anyMatch(r->!r.children.isEmpty()),"healthy actual project setup");
            check(MinecraftObjectHolder.DEBUGGING,"DEBUGGING positive JVM required");
            Throwable failure=null;try{publicExport(e,"debug-owned");}catch(Throwable t){failure=t;}
            check(failure==null,"OWNED compiler must suppress payload log before logger; actual failure="+failure);
        }
    }
}
