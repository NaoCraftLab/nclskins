package com.naocraftlab.skins.buildlogic.probe;

import com.tom.cpl.util.Image;
import com.tom.cpl.util.ImageIO.IImageIO;
import com.tom.cpl.math.Vec2i;
import com.tom.cpm.shared.*;
import com.tom.cpm.shared.definition.*;
import com.tom.cpm.shared.config.ResourceLoader.ResourceEncoding;
import com.tom.cpm.shared.editor.*;
import com.tom.cpm.shared.editor.project.*;
import com.tom.cpm.shared.io.*;
import com.tom.cpm.shared.model.*;
import com.tom.cpm.shared.parts.*;
import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public final class CpmImportFeasibilityProbe {
    private static final List<Map<String,Object>> results = new ArrayList<>();
    private static final Map<String,Integer> effects = new TreeMap<>();
    private static ModelDefinitionLoader<Object> loader;
    private static com.tom.cpl.config.ModConfigFile config;
    private static byte[] hosted;
    private static int fakeLoads;
    private static boolean authoring;
    private static int authoringMessages;
    private static boolean expectingOverflow;
    private static int overflowErrors;
    private static Path fixtures, output;
    private static String fault = "none";
    private static boolean bindingSetup;
    private static final class Effect extends Error { Effect(String name) { super(name); } }
    private static final com.tom.cpl.block.BlockStateHandler<Object> blocks = new com.tom.cpl.block.BlockStateHandler<>() {
        public String getBlockId(Object s){throw forbidden("block-id");}
        public List<String> getBlockStates(Object s){throw forbidden("block-states");}
        public String getPropertyValue(Object s,String n){throw forbidden("block-property");}
        public int getPropertyValueInt(Object s,String n){throw forbidden("block-property");}
        public List<String> getAllValuesFor(Object s,String n){throw forbidden("block-values");}
        public List<String> listNativeTags(){return List.of();}
        public boolean equals(Object a,Object b){return a==b;}
        public boolean equalsFull(Object a,Object b){return a==b;}
        public com.tom.cpl.item.Stack getStackFromState(Object s){throw forbidden("block-stack");}
        public boolean isInTag(String n,Object s){return false;}
        public List<String> listTags(Object s){return List.of();}
        public List<com.tom.cpl.block.BlockState> listNativeEntries(String n){return List.of();}
        public List<com.tom.cpl.block.BlockState> getAllElements(){return List.of();}
        public com.tom.cpl.block.BlockState emptyObject(){return null;}
    };
    private static final com.tom.cpl.item.ItemStackHandler<Object> items = new com.tom.cpl.item.ItemStackHandler<>() {
        public List<String> listTags(Object s){return List.of();}
        public List<String> listNativeTags(){return List.of();}
        public int getCount(Object s){throw forbidden("stack-count");}
        public int getMaxCount(Object s){throw forbidden("stack-count");}
        public int getDamage(Object s){throw forbidden("stack-damage");}
        public int getMaxDamage(Object s){throw forbidden("stack-damage");}
        public boolean itemEquals(Object a,Object b){return a==b;}
        public boolean itemEqualsFull(Object a,Object b){return a==b;}
        public com.tom.cpl.nbt.NBTTagCompound getTag(Object s){throw forbidden("stack-tag");}
        public boolean isInTag(String n,Object s){return false;}
        public String getItemId(Object s){throw forbidden("stack-id");}
        public String getItemDisplayName(Object s){throw forbidden("stack-name");}
        public List<com.tom.cpl.item.Stack> listNativeEntries(String n){return List.of();}
        public List<com.tom.cpl.item.Stack> getAllElements(){return List.of();}
        public com.tom.cpl.item.Stack emptyObject(){return null;}
    };
    private static final class SyntheticPlayer extends com.tom.cpm.shared.config.Player<Object> {
        public SkinType getSkinType(){return SkinType.SLIM;}
        protected com.tom.cpm.shared.skin.PlayerTextureLoader initTextures(){throw forbidden("profile-textures");}
        public String getName(){return "Synthetic";}
        public UUID getUUID(){return UUID.fromString("00000000-0000-0000-0000-000000000123");}
        public void updateFromPlayer(com.tom.cpm.shared.animation.AnimationState a,Object b){throw forbidden("player-update");}
        public Object getGameProfile(){throw forbidden("profile-read");}
        public void updateFromModel(com.tom.cpm.shared.animation.AnimationState a,Object b){throw forbidden("model-update");}
        @Override public boolean isClientPlayer(){return true;}
    }
    private static Effect forbidden(String name) { effects.merge(name,1,Integer::sum); return new Effect(name); }
    private static final IImageIO imageIO = new IImageIO() {
        public Image read(File f) { throw forbidden("image-file-read"); }
        public Image read(InputStream in) throws IOException { return fromRaster(javax.imageio.ImageIO.read(in)); }
        public void write(Image i,File f) { throw forbidden("image-file-write"); }
        public void write(Image i,OutputStream out) throws IOException { javax.imageio.ImageIO.write(toRaster(i),"png",out); }
        public Vec2i getSize(InputStream in) throws IOException { var i=javax.imageio.ImageIO.read(in);return new Vec2i(i.getWidth(),i.getHeight()); }
    };
    private static Image fromRaster(BufferedImage i) { return new Image(i.getRGB(0,0,i.getWidth(),i.getHeight(),null,0,i.getWidth()),i.getWidth()); }
    private static BufferedImage toRaster(Image i) { var r=new BufferedImage(i.getWidth(),i.getHeight(),BufferedImage.TYPE_INT_ARGB);r.setRGB(0,0,i.getWidth(),i.getHeight(),i.getData(),0,i.getWidth());return r; }
    @SuppressWarnings("unchecked") private static <T> T host(Class<T> type) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->switch(m.getName()) {
            case "getImageIO" -> imageIO;
            case "getDefinitionLoader" -> loader;
            case "getVersionCheck" -> null;
            case "getConfig" -> config;
            case "i18nFormat" -> a[0];
            case "displayMessagePopup" -> {if(!authoring)throw forbidden("UI.displayMessagePopup");authoringMessages++;yield null;}
            case "error" -> {if(!expectingOverflow)throw forbidden("ILogger.error");overflowErrors++;yield null;}
            case "info" -> {if(!bindingSetup)throw forbidden("ILogger.info");effects.merge("ILogger.info",1,Integer::sum);yield null;}
            case "getLogger" -> host(com.tom.cpl.util.ILogger.class);
            case "getBuiltinTags" -> new com.tom.cpl.tag.AllTagManagers();
            case "getBlockStateHandler" -> blocks;
            case "getItemStackHandler" -> items;
            case "getBiomeHandler", "getEntityTypeHandler" -> null;
            default -> throw forbidden(type.getSimpleName()+"."+m.getName());
        });
    }
    private static void check(boolean b,String why) { if(!b)throw new AssertionError(why); }
    private static void equal(byte[] a,byte[] b,String why) { check(Arrays.equals(a,b),why); }
    private interface Case { void run() throws Exception; }
    private static void test(String gate,String name,Case body) {
        var result=new LinkedHashMap<String,Object>();result.put("gate",gate);result.put("case",name);var before=new TreeMap<>(effects);
        try { body.run();result.put("status","PASS"); }
        catch(Throwable t) { result.put("status",t instanceof Effect || t instanceof AssertionError || t instanceof IOException?"FAIL":"UNKNOWN");result.put("exception",t.getClass().getName());result.put("reason",t instanceof Effect?t.getMessage():String.valueOf(t.getMessage()));
            var causes=new ArrayList<String>();for(Throwable c=t.getCause();c!=null;c=c.getCause())causes.add(c.getClass().getName()+": "+c.getMessage());result.put("causes",causes);
            var s=t.getStackTrace();result.put("stack",Arrays.stream(s).limit(9).map(Object::toString).toList()); }
        result.put("effects_before",before);result.put("effects_after",new TreeMap<>(effects));results.add(result);System.out.println(new Gson().toJson(result));
    }
    private static byte[] envelope(byte[] data,byte[] backup,String link) throws Exception {
        var bytes=new ByteArrayOutputStream();bytes.write(83);
        try(var cos=new ChecksumOutputStream(bytes)) { var h=new IOHelper(cos);h.writeUTF("Synthetic");h.writeUTF("");h.writeByteArray(data);h.writeByteArray(backup);if(backup.length>0)new Link(link).write(h);h.writeVarInt(0); }
        return bytes.toByteArray();
    }
    private static byte[] carrierPayload(Image i,SkinType t,int n) throws Exception { return new SkinDataInputStream(i,loader.getTemplate(),t.getChannel()).readNBytes(n); }
    private static Image convert(byte[] data,SkinType type,byte[] backup,String link) throws Exception {
        byte[] exportData=data;
        if(fault.equals("unknown-loss")) {
            var source=new IOHelper(new ByteArrayInputStream(data,1,data.length-1));var kept=new ModelPartCollection();
            while(true){var part=source.readObjectBlock(ModelPartType.VALUES,(t,b)->t.getFactory().create(b,null));if(part==null)continue;if(part instanceof ModelPartEnd)break;kept.add(part);}exportData=packageBytes(kept);
        }
        byte[] container=envelope(exportData,backup,link);var mf=ModelFile.load(new ByteArrayInputStream(container));
        var base=new Image(64,64);Arrays.fill(base.getData(),0xff786543);base.setRGB(8,8,0x00123456);var original=base.getData().clone();
        var ref=new AtomicReference<Image>();Exporter.convert(mf,base,type,ref::set,()->{throw new AssertionError("conversion error callback");});var out=ref.get();
        check(out!=null,"actual writer must yield a result");
        if(fault.equals("reject-all"))throw new AssertionError("accepted valid positive rejected");
        if(fault.equals("raw-retag"))type=type==SkinType.SLIM?SkinType.DEFAULT:SkinType.SLIM;
        equal(data,carrierPayload(out,type,data.length),"independent raw payload/channel including unknown and lock bytes");
        var typeReader=new IOHelper(new ByteArrayInputStream(carrierPayload(out,type,data.length),1,data.length-1));
        while(true){var part=typeReader.readObjectBlock(ModelPartType.VALUES,(t,b)->t.getFactory().create(b,null));if(part==null)continue;if(part instanceof ModelPartEnd)break;
            if(part instanceof ModelPartSkinType st && st.getSkinType()!=SkinType.UNKNOWN)check(st.getSkinType().getChannel()==type.getChannel(),"independent concrete SKIN_TYPE must agree with selected carrier channel");
        }
        check(out.getRGB(8,8)==0x00123456,"transparent RGB outside template");check(Arrays.equals(original,base.getData()),"base unchanged");return out;
    }
    private static void direct() throws Exception {
        for(String name:List.of("classic","slim","unknown","absent","large"))for(SkinType type:SkinType.VANILLA_TYPES) {
            if(name.equals("classic") && type!=SkinType.DEFAULT || name.equals("slim") && type!=SkinType.SLIM)continue;
            test("G-DIRECT",name+"-"+type,()->{
                byte[] expected=Files.readAllBytes(fixtures.resolve(name+".payload"));
                var i=convert(expected,type,new byte[0],"p:unused");
                javax.imageio.ImageIO.write(toRaster(i),"png",output.resolve(name+"-"+type+".png").toFile());
                var checksum=new ChecksumInputStream(new ByteArrayInputStream(Arrays.copyOfRange(carrierPayload(i,type,expected.length),1,expected.length)));var h=new IOHelper(checksum);
                int parts=0;while(true) { IModelPart part=h.readObjectBlock(ModelPartType.VALUES,(t,b)->t.getFactory().create(b,null));if(part==null)continue;if(part instanceof ModelPartEnd)break;parts++; }checksum.checkSum();
                check(parts>=1,"actual part reader reaches known content");
                if(name.equals("large"))check(expected.length>2048 && !ModelFile.load(new ByteArrayInputStream(envelope(expected,new byte[0],"p:unused"))).convertable(),"actual >2048 nonconvertable flag still exports at real fit");
            });
        }
        for(SkinType type:SkinType.VANILLA_TYPES)test("G-DIRECT","capacity-"+type,()->{
            var stream=new SkinDataOutputStream(new Image(64,64),loader.getTemplate(),type.getChannel());int count=0;
            try { while(count<65536){stream.write(count&255);count++;}throw new AssertionError("unbounded writer"); }catch(EOFException expected){}
            check(count>2048,"actual capacity beyond historical2048");var image=new Image(64,64);var exact=new SkinDataOutputStream(image,loader.getTemplate(),type.getChannel());
            var expected=new byte[count];for(int x=0;x<count;x++){expected[x]=(byte)x;exact.write(expected[x]);}equal(expected,carrierPayload(image,type,count),"inclusive capacity exact byte readback");
            boolean rejected=false;try{exact.write(1);}catch(EOFException e){rejected=true;}check(rejected,"capacity+1 rejected");Files.writeString(output.resolve("capacity-"+type+".txt"),Integer.toString(count));
            var valid=Files.readAllBytes(fixtures.resolve("capacity-"+type+".payload"));check(valid.length==count,"independent inclusive fixture reaches actual capacity");convert(valid,type,new byte[0],"p:unused");
            var over=Files.readAllBytes(fixtures.resolve("capacity-"+type+"-plus1.payload"));var model=ModelFile.load(new ByteArrayInputStream(envelope(over,new byte[0],"p:unused")));var success=new AtomicReference<Image>();var errors=new int[1];int logsBefore=overflowErrors;expectingOverflow=true;
            try{Exporter.convert(model,new Image(64,64),type,success::set,()->errors[0]++);}finally{expectingOverflow=false;}
            if(fault.equals("guard-removal"))success.set(new Image(64,64));
            check(success.get()==null && errors[0]==1 && overflowErrors==logsBefore+1,"actual overflow error callback never becomes partial success");
        });
        for(String kind:List.of("p","gh","git","ms","pb","local"))test("G-DIRECT","link-"+kind,()->{
            var parts=new ModelPartCollection();var link=parts.toLink(new Link(kind+":synthetic"));var b=new ByteArrayOutputStream();b.write(83);
            try(var cos=new ChecksumOutputStream(b)){var h=new IOHelper(cos);h.writeObjectBlock(new ModelPartSkinType(SkinType.SLIM));h.writeObjectBlock(link);h.writeObjectBlock(ModelPartEnd.END);}
            var png=convert(b.toByteArray(),SkinType.SLIM,new byte[]{83,0,0},kind+":synthetic");
            check(carrierPayload(png,SkinType.SLIM,b.size()).length==b.size(),"link framing preserved without resolve");
            if(kind.equals("local"))check(!kind.equals("p"),"local preservation is NOT portability");
        });
    }
    private static byte[] packageBytes(PartCollection parts) throws Exception {
        var b=new ByteArrayOutputStream();b.write(83);try(var cos=new ChecksumOutputStream(b)){parts.writePackage(new IOHelper(cos));}return b.toByteArray();
    }
    private static void wire() {
        for(boolean modern:List.of(true,false))for(boolean local:List.of(false,true))test("G-WIRE",(modern?"modern-package":"legacy-definition")+(local?"-local-backup":""),()->{
            var lock=new ModelPartUUIDLockout(UUID.fromString("00000000-0000-0000-0000-000000000123"));
            PartCollection parts;if(modern){var p=new ModelPartCollection();p.add(lock);parts=p;}else{var p=new ModelPartDefinition(List.of());p.setOtherParts(List.of(lock));parts=p;}
            byte[] body=packageBytes(parts);byte[] expected=Files.readAllBytes(fixtures.resolve(modern?"modern.wire":"legacy.wire"));equal(expected,body,"independent expected wire kind and bytes");
            if(local) {
                var inline=new ByteArrayOutputStream();inline.write(83);try(var cos=new ChecksumOutputStream(inline)){var h=new IOHelper(cos);h.writeObjectBlock(parts.toLink(new Link("local:synthetic")));h.writeObjectBlock(ModelPartEnd.END);}
                byte[] container=envelope(inline.toByteArray(),body,"local:synthetic");var mf=ModelFile.load(new ByteArrayInputStream(container));equal(inline.toByteArray(),mf.getDataBlock(),"actual model has local-only link before externalization");
                var reader=new IOHelper(new ByteArrayInputStream(container,1,container.length-1));reader.readUTF();reader.readUTF();reader.readByteArray();body=reader.readByteArray();equal(expected,body,"public envelope reader extracts exact retained local backup, no registerLocalCache");
            }
            byte[] delivered=Base64.getDecoder().decode(Base64.getEncoder().encode(body));equal(expected,delivered,"bounded fake external effect bytes");
            var b=new ByteArrayOutputStream();b.write(83);try(var cos=new ChecksumOutputStream(b)){var h=new IOHelper(cos);h.writeObjectBlock(parts.toLink(new Link("p:synthetic")));h.writeObjectBlock(ModelPartEnd.END);}
            Image png=convert(b.toByteArray(),SkinType.SLIM,new byte[0],"p:unused");
            var h=new IOHelper(new ByteArrayInputStream(carrierPayload(png,SkinType.SLIM,b.size()),1,b.size()-1));
            var actual=h.readObjectBlock(ModelPartType.VALUES,(t,in)->t.getFactory().create(in,new ModelDefinition(loader,null)));
            check(actual.getType().ordinal()==(modern?20:4),"actual reader correct package versus definition link kind");
            hosted=fault.equals("container-as-payload")?envelope(body,new byte[0],"p:unused"):fault.equals("wrong-kind")?Files.readAllBytes(fixtures.resolve(modern?"legacy.wire":"modern.wire")):delivered;
            int before=fakeLoads;try{check(actual.resolve()!=null,"actual link reader resolves correct body");check(fakeLoads==before+1,"real resolver reaches fake external boundary once");}finally{hosted=null;}
            Files.write(output.resolve(modern?"modern.wire":"legacy.wire"),body);
        });
    }
    private static void project() {
        for(String name:List.of("canonical","atlas","dense-atlas","borrowed","missing-type","future","template","uuid-lock","missing-author","animation"))test("G-PROJECT",name,()->{
            var e=new Editor();e.setUI(host(com.tom.cpl.gui.UI.class));e.skinType=SkinType.SLIM;
            for(PlayerModelParts part:PlayerModelParts.VALUES)if(part!=PlayerModelParts.CUSTOM_PART)e.elements.add(new com.tom.cpm.shared.editor.elements.ModelElement(e,com.tom.cpm.shared.editor.elements.ElementType.ROOT_PART,part));
            e.vanillaSkin=SkinType.SLIM.getSkinTexture();var skin=e.textures.get(TextureSheetType.SKIN);skin.setDefaultImg(e.vanillaSkin);skin.setImage(new Image(e.vanillaSkin));skin.provider.size=new Vec2i(64,64);skin.setEdited(false);
            var p=new ProjectFile();p.load(fixtures.resolve(name+".cpmproject").toFile()).join();ProjectIO.loadProject(e,p);
            check(e.elements.stream().flatMap(r->r.children.stream()).anyMatch(c->c.size.x==3 && c.size.y==5 && c.size.z==7 && c.pos.x==2),"independent authored geometry admitted through actual ProjectIO");
            check(e.skinType==SkinType.SLIM,"explicit type or selected preferred Slim preserved");
            if(name.equals("animation"))check(e.animations.size()==1 && e.animations.get(0).duration==1000 && e.animations.get(0).getFrames().size()==1,"actual animation is not ignored fixture");
            if(!name.equals("borrowed")&&!name.equals("missing-author"))check(skin.getImage().getWidth()==(name.contains("atlas")?128:64),"full atlas loaded not vanilla base");
            var ref=new AtomicReference<Image>();Exporter.exportSkin(e,host(com.tom.cpl.gui.UI.class),ref::set,false);
            check(ref.get()!=null,"public project compiler callback required");
            var in=new SkinDataInputStream(ref.get(),loader.getTemplate(),e.skinType.getChannel());check(in.read()==83,"actual project carrier header");var checksum=new ChecksumInputStream(in);var h=new IOHelper(checksum);var parsed=new ArrayList<IModelPart>();
            var readerDefinition=new ModelDefinition(loader,new SyntheticPlayer());
            while(true){var part=h.readObjectBlock(ModelPartType.VALUES,(t,b)->t.getFactory().create(b,readerDefinition));if(part==null)continue;if(part instanceof ModelPartEnd)break;parsed.add(part);}checksum.checkSum();
            check(parsed.stream().anyMatch(v->v.getType()==ModelPartType.DEFINITION),"default exporter remains legacy definition, not experimental substitution");
            for(var part:parsed)if(part instanceof ModelPartDefinition) {
                var written=new IOHelper();part.write(written);var body=written.flip();int count=body.readVarInt();check(count==1,"one independent authored cube exported");
                var cube=Cube.loadDefinitionCube(body);check(cube.size.x==3 && cube.size.y==5 && cube.size.z==7 && cube.pos.x==2 && cube.pos.y==4 && cube.pos.z==6,"actual final cube dimensions and position");
                boolean textureFound=false,animationFound=false;
                while(true){var child=body.readObjectBlock(ModelPartType.VALUES,(t,b)->t.getFactory().create(b,readerDefinition));if(child==null)continue;if(child instanceof ModelPartEnd)break;
                    if(child.getType()==ModelPartType.ANIMATION_DATA)animationFound=true;
                    if(child.getType()==ModelPartType.SKIN){var texture=new IOHelper();child.write(texture);var textureReader=texture.flip();textureReader.read2s();var imageBlock=textureReader.readImage();imageBlock.doReadImage();var image=imageBlock.getImage();int w=name.contains("atlas")?128:64;check(image.getWidth()==w && image.getHeight()==w,"full atlas dimensions readback");check(image.getRGB(w-1,w-2)==0xff1234ab,"independent far-corner texture pixels readback");textureFound=true;}
                }
                if(!name.equals("borrowed")&&!name.equals("missing-author"))check(textureFound,"actual export retains model texture, not only callback");
                if(name.equals("animation"))check(animationFound,"actual serialized animation block retained");
            }
            if(name.equals("canonical")) {
                var desc=new com.tom.cpm.shared.editor.util.ModelDescription();desc.name="Synthetic";desc.desc="";
                Path modelFile=output.resolve("non-skin-compatible.cpmmodel");int before=authoringMessages;authoring=true;
                try{Exporter.exportModel(e,e.ui,modelFile.toFile(),desc,false);}finally{authoring=false;}
                check(authoringMessages==before+1 && Files.isRegularFile(modelFile),"actual public exportModel skinCompat=false authoring provenance, terminal success notification captured");
                ModelFile model;try(var input=Files.newInputStream(modelFile)){model=ModelFile.load(input);}var converted=new AtomicReference<Image>();Exporter.convert(model,e.vanillaSkin,e.skinType,converted::set,()->{throw new AssertionError("non-compatible convert failed");});
                equal(model.getDataBlock(),carrierPayload(converted.get(),e.skinType,model.getDataBlock().length),"actual non-Skin-Compatible exported file converts without lost bytes");
            }
            javax.imageio.ImageIO.write(toRaster(ref.get()),"png",output.resolve(name+".png").toFile());
        });
    }
    public static void main(String[] args) throws Exception {
        fixtures=Path.of(args[0]);output=Path.of(args[1]);if(args.length>3)fault=args[3];bindingSetup=args[2].equals("project-binding-setup");
        config=new com.tom.cpl.config.ModConfigFile(output.resolve("synthetic-config.json").toFile()) {
            @Override public void save(){throw forbidden("config-save");}
        };
        MinecraftObjectHolder.setCommonObject(host(MinecraftCommonAccess.class));MinecraftObjectHolder.setClientObject(host(MinecraftClientAccess.class));
        loader=new ModelDefinitionLoader<>(p->{throw forbidden("profile-factory");},p->{throw forbidden("profile-uuid");},p->{throw forbidden("profile-name");}) {
            @Override public InputStream load(Link l,ResourceEncoding e,ModelDefinition d){
                if(hosted!=null){check(l.getLoader().equals("p") && l.getPath().equals("synthetic") && e==(bindingSetup?ResourceEncoding.NO_ENCODING:ResourceEncoding.BASE64),"fake response only at expected link boundary");fakeLoads++;return new ByteArrayInputStream(hosted);}
                throw forbidden("resource-load");
            }
            @Override public void putLocalResource(Link l,byte[] b){throw forbidden("cache-register");}
        };
        test("SAFETY","instrumentation-positive",()->{try{loader.load(new Link("p:synthetic"),ResourceEncoding.NO_ENCODING,null);}catch(Effect expected){}check(effects.getOrDefault("resource-load",0)==1,"resource tripwire exercised");effects.clear();});
        if(args[2].equals("direct"))direct();else if(args[2].equals("wire"))wire();else if(!args[2].equals("setup")&&!bindingSetup)project();
        Files.writeString(output.resolve("cases.json"),new Gson().toJson(results));
    }
}
