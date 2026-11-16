package com.naocraftlab.skins.probe;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.*;
import com.unascribed.ears.api.Slice;
import com.unascribed.ears.api.features.*;
import com.unascribed.ears.api.features.EarsFeatures.*;
import com.unascribed.ears.common.*;
import diy.y2k.five.thermite.*;
import net.minecraft.client.model.geom.*;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.state.*;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.*;
import java.lang.reflect.*;
import java.util.*;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.concurrent.*;
import net.minecraft.client.renderer.texture.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import com.naocraftlab.skins.compat.client.identifier.IdentifierTextureRegistry;
import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import java.security.MessageDigest;

public final class EarsProbe {
    static EarsFeatures selected;
    static final Map<RenderType,Identifier> materials = new IdentityHashMap<>();
    static final sun.misc.Unsafe U;
    static { try {Field f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);U=(sun.misc.Unsafe)f.get(null);}catch(Exception e){throw new ExceptionInInitializerError(e);} }
    static Observation drawingObservation;
    public static EarsFeatures selectedFeatures(AvatarRenderState peer){require(drawingObservation!=null&&drawingObservation.current()&&drawingObservation.id().equals(peer.skin.body().texturePath()),"native delegate reads exact current processed selected texture");return selected; }
    static int networkAttempts;
    static volatile CompletableFuture<?> lastTerminal;
    static volatile NativeImage lastIoImage;
    static volatile Path lastIoPath;
    static final Map<Object,Integer> textureCloses = Collections.synchronizedMap(new IdentityHashMap<>());
    static final Map<Object,Integer> imageCloses = Collections.synchronizedMap(new IdentityHashMap<>());
    static volatile boolean failNextCreation;
    static int nativeCreations;
    static boolean inlineIo;
    static void plainCases() throws Exception {
        byte[] bytes=png(64,64);
        IdentifierTextureRegistry first=start(bytes);Identifier id=source;Runnable callback=queued();NativeImage image=lastIoImage;Path path=lastIoPath;
        callback.run();
        AbstractTexture texture=Host.INSTANCE.textures.map.get(id);
        require(texture instanceof DynamicTexture&&!(texture instanceof FeaturesHolder)&&NativePlayerSkinLifecycle.isReady(id.toString()),"native vanilla downloader still registers ordinary DynamicTexture and reaches READY");
        first.close();first.close();
        require(closes(imageCloses,image)==1&&closes(textureCloses,texture)==1&&!Files.exists(path),"plain positive cleanup exactly once");
        casePass("plain-positive");
        first=start(bytes);callback=queued();image=lastIoImage;CompletableFuture<?> done=lastTerminal;first.close();
        IdentifierTextureRegistry replacement=start(bytes);Runnable replacementCallback=queued();replacementCallback.run();
        texture=Host.INSTANCE.textures.map.get(id);callback.run();terminal(done,true);
        require(Host.INSTANCE.textures.map.get(id)==texture&&closes(textureCloses,texture)==0&&closes(imageCloses,image)==1,"plain queued stale registration preserves exact replacement");
        replacement.close();casePass("plain-queued-same-id");
        holdNextIo.set(true);first=start(bytes);require(oldIoEntered.await(15,TimeUnit.SECONDS),"plain original I/O reaches controlled gate");
        image=lastIoImage;done=lastTerminal;first.close();
        replacement=start(bytes);replacementCallback=queued();replacementCallback.run();texture=Host.INSTANCE.textures.map.get(id);
        releaseOldIo.countDown();terminal(done,true);
        require(Host.INSTANCE.textures.map.get(id)==texture&&closes(textureCloses,texture)==0&&closes(imageCloses,image)==1&&Host.INSTANCE.queue.isEmpty(),"plain late I/O cannot register or release replacement");
        replacement.close();casePass("plain-io-same-id");
        require(networkAttempts==0,"plain native route creates no network attempt");
        System.out.println("PLAIN_NATIVE_LIFETIME_PASS cases=3 networkAttempts=0");
    }

    public static <T> CompletableFuture<T> ioSupply(java.util.function.Supplier<T> supplier,Executor executor) {
        return CompletableFuture.supplyAsync(supplier,inlineIo?Runnable::run:executor);
    }
    static void inlineCases() throws Exception {
        byte[] bytes;
        try(Processed seed=fixture(EarMode.TALL)) {bytes=Files.readAllBytes(Path.of(System.getProperty("probe.dir")).resolve("synthetic-TALL.png"));}
        inlineIo=true;
        try {
            IdentifierTextureRegistry registry=start(bytes);NativeImage image=lastIoImage;Path path=lastIoPath;
            require(NativePlayerSkinLifecycle.isReady(source.toString())&&observe(source)!=null,"real inline completion is READY before registerSkin returns");
            registry.close();registry.close();
            require(closes(imageCloses,image)==1&&!Files.exists(path)&&Host.INSTANCE.textures.map.get(source)==null,"inline completion cleanup exactly once");
            casePass("inline-completion");
            IdentifierTextureRegistry[] owner=new IdentifierTextureRegistry[1];
            Constructor<IdentifierTextureRegistry> ctor=IdentifierTextureRegistry.class.getDeclaredConstructor(String.class,Runnable.class);ctor.setAccessible(true);
            owner[0]=ctor.newInstance("dynamic",(Runnable)()->owner[0].close());
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            try {owner[0].registerSkin(digest,bytes);throw new AssertionError("reentrant closed registerSkin must reject publication");}
            catch(IllegalStateException expected){require(expected.getMessage().equals("Texture registry is closed"),"actual closed admission rejection");}
            image=lastIoImage;path=lastIoPath;owner[0].close();
            require(closes(imageCloses,image)==1&&!Files.exists(path)&&Host.INSTANCE.textures.map.get(source)==null,"inline reentrant close cannot leave acquired native texture or staging");
            casePass("inline-reentrant-close");
            failNextCreation=true;registry=start(bytes);image=lastIoImage;path=lastIoPath;
            require(NativePlayerSkinLifecycle.managedState(source.toString()).orElseThrow()==NativePlayerSkinLifecycle.State.FAILED,"inline failure remains failed, never READY");
            registry.close();registry.close();
            require(closes(imageCloses,image)==1&&!Files.exists(path)&&Host.INSTANCE.textures.map.get(source)==null,"inline failure cleans original image and staging exactly once");
            casePass("inline-native-failure");
            require(networkAttempts==0&&Host.INSTANCE.queue.isEmpty(),"inline matrix uses no network or residual client queue");
            System.out.println("INLINE_NATIVE_LIFETIME_PASS cases=3 networkAttempts=0");
        } finally {inlineIo=false;}
    }

    static boolean retainedPredecessor;
    public static CompletableFuture<?> terminal(CompletableFuture<?> future) { lastTerminal = future; return future; }
    public static void ioPath(Path path) { lastIoPath = path; }
    public static void closedTexture(Object texture) { textureCloses.merge(texture,1,Integer::sum); }
    public static void closedImage(Object image) { imageCloses.merge(image,1,Integer::sum); }
    public static void createTexture() {
        nativeCreations++;
        if (failNextCreation) { failNextCreation=false; throw new IllegalStateException("synthetic native creation failure"); }
    }
    static void terminal(CompletableFuture<?> future, boolean failed) throws Exception {
        try { future.get(15,TimeUnit.SECONDS); require(!failed,"original future must fail"); }
        catch (ExecutionException expected) {
            require(failed,"original future must succeed");
            Throwable cause=expected.getCause();
            require(cause instanceof java.util.concurrent.CancellationException
                    || cause instanceof IllegalStateException&&"synthetic native creation failure".equals(cause.getMessage()),"original future fails only for the exercised lifecycle cause");
            require(cause.getSuppressed().length==0,"no hidden cleanup failure");
        }
        catch (java.util.concurrent.CancellationException expected) { require(failed,"original future must succeed"); }
    }
    static int closes(Map<Object,Integer> counts,Object resource) { return counts.getOrDefault(resource,0); }
    static void casePass(String name) { System.out.println("LIFETIME_CASE_PASS " + name); }
    static final Map<Identifier, byte[]> sourceContents = new HashMap<>();
    static IdentifierTextureRegistry start(byte[] bytes) throws Exception {
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        IdentifierTextureRegistry registry=new IdentifierTextureRegistry();
        source=Identifier.parse(registry.registerSkin(digest,bytes).location());
        sourceContents.put(source, bytes.clone());
        return registry;
    }
    static Runnable queued() throws Exception {
        Runnable callback=Host.INSTANCE.queue.poll(15,TimeUnit.SECONDS);
        require(callback!=null,"actual native producer queued registration");return callback;
    }
    static void nativeCases() throws Exception {
        byte[] bytes;
        try(Processed seed=fixture(EarMode.TALL)) { bytes=Files.readAllBytes(Path.of(System.getProperty("probe.dir")).resolve("synthetic-TALL.png")); }
        for(boolean sameId:new boolean[]{true,false}) {
            for(boolean retire:new boolean[]{true,false}) {
                IdentifierTextureRegistry old=start(bytes); Identifier oldId=source;
                Runnable callback=queued(); NativeImage image=lastIoImage;Path path=lastIoPath;
                CompletableFuture<?> done=lastTerminal;
                require(Files.isRegularFile(path),"pending original I/O owns staged file");
                if(retire) old.close();
                try(Processed replacement=fixture(sameId?EarMode.TALL:EarMode.BEHIND)) {
                    require(sameId==source.equals(oldId),"same/different ID discriminator");
                    EarsTexture current=replacement.texture();
                    int creationsBefore=nativeCreations;
                    callback.run();
                    if(retire||sameId) require(nativeCreations==creationsBefore,"retired or replaced queue must not create a late native texture");
                    terminal(done,retire||sameId);
                    if(!sameId&&!retire) require(Host.INSTANCE.textures.map.get(oldId)!=null,"different-ID live owner remains admitted");
                    else require(Host.INSTANCE.textures.map.get(oldId)==(sameId?current:null),"retired or replaced queue cannot register over replacement");
                    require(Host.INSTANCE.textures.map.get(source)==current,"queued old completion preserves exact replacement");
                    old.close(); old.close();
                    require(Host.INSTANCE.textures.map.get(source)==current,"old cleanup preserves replacement after queued acquisition");
                    require(!Files.exists(path),"terminal original future closes staged file");
                    require(closes(imageCloses,image)==1,"old native image closes exactly once");
                    require(closes(textureCloses,current)==0,"replacement remains open");
                } finally {old.close();}
                casePass("queued-"+(sameId?"same-id":"different-id")+"-"+(retire?"retired":"live"));
            }
            Processed old=fixture(EarMode.TALL);EarsTexture prior=old.texture();
            retainedPredecessor=true;
            try(Processed replacement=fixture(sameId?EarMode.TALL:EarMode.BEHIND)) {
                EarsTexture current=replacement.texture();
                require(closes(textureCloses,prior)==(sameId?1:0),"native map replacement closes only displaced object");
                old.close();old.close();
                require(closes(textureCloses,prior)==1,"acquired old texture closes exactly once");
                require(Host.INSTANCE.textures.map.get(source)==current&&closes(textureCloses,current)==0,"acquired old cleanup cannot delete replacement");
            } finally {old.close();retainedPredecessor=false;}
            casePass("acquired-"+(sameId?"same-id":"different-id"));
        }
        IdentifierTextureRegistry failed=start(bytes);Runnable callback=queued();
        NativeImage image=lastIoImage;Path path=lastIoPath;CompletableFuture<?> done=lastTerminal;
        failNextCreation=true; callback.run(); terminal(done,true);
        require(NativePlayerSkinLifecycle.managedState(source.toString()).orElseThrow()==NativePlayerSkinLifecycle.State.FAILED,"native creation failure publishes failed, not READY");
        require(closes(imageCloses,image)==1,"failed native creation closes unregistered image exactly once");
        require(!Files.exists(path)&&Host.INSTANCE.textures.map.get(source)==null,"failed original future closes staging and leaves no registration");
        failed.close();failed.close();
        casePass("native-creation-failure");
        IdentifierTextureRegistry[] owner=new IdentifierTextureRegistry[1];
        Constructor<IdentifierTextureRegistry> ctor=IdentifierTextureRegistry.class.getDeclaredConstructor(String.class,Runnable.class);
        ctor.setAccessible(true);
        owner[0]=ctor.newInstance("dynamic",(Runnable)()->{if(NativePlayerSkinLifecycle.isReady(source.toString()))owner[0].close();});
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        source=Identifier.parse(owner[0].registerSkin(digest,bytes).location());
        callback=queued();done=lastTerminal;image=lastIoImage;path=lastIoPath;
        callback.run();terminal(done,false);owner[0].close();
        require(!Files.exists(path)&&Host.INSTANCE.textures.map.get(source)==null&&closes(imageCloses,image)==1,"reentrant readiness close releases actual acquired texture exactly once");
        casePass("reentrant-ready-close");
        require(networkAttempts==0,"native lifetime matrix performs no network attempts");
        System.out.println("NATIVE_LIFETIME_MATRIX_PASS cases=8 networkAttempts=0");
    }

    static volatile CompletableFuture<?> lastDownload;
    public static CompletableFuture<?> downloaded(CompletableFuture<?> future) { lastDownload = future; return future; }
    static final java.util.concurrent.atomic.AtomicBoolean holdNextIo = new java.util.concurrent.atomic.AtomicBoolean();
    static final CountDownLatch oldIoEntered = new CountDownLatch(1);
    static final CountDownLatch releaseOldIo = new CountDownLatch(1);
    public static NativeImage holdOldIo(NativeImage image) {
        lastIoImage=image;
        if (holdNextIo.getAndSet(false)) {
            oldIoEntered.countDown();
            try { require(releaseOldIo.await(15, TimeUnit.SECONDS), "bounded old I/O gate released"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        }
        return image;
    }
    public static java.net.URLConnection forbiddenNetwork(java.net.URL url,java.net.Proxy proxy){networkAttempts++;throw new AssertionError("network forbidden in isolated probe");}
    public static int overlay(LivingEntityRenderState s,float p){return 0;}
    public static final class Host implements Executor {
        static final Host INSTANCE=new Host();
        final BlockingQueue<Runnable> queue=new LinkedBlockingQueue<>();
        final Textures textures=new Textures();
        final Thread main=Thread.currentThread();
        public java.net.Proxy getProxy(){return java.net.Proxy.NO_PROXY;}
        public boolean isSameThread(){return Thread.currentThread()==main;}
        public static Host getInstance(){return INSTANCE;}
        public TextureManager getTextureManager(){return textures;}
        public void execute(Runnable r){if(inlineIo&&isSameThread())r.run();else queue.add(r);}
    }
    public static final class Textures extends TextureManager {
        final Map<Identifier,AbstractTexture> map;
        @SuppressWarnings("unchecked")
        Textures() {
            super(null);
            try {
                Field field = TextureManager.class.getDeclaredField("byPath");
                field.setAccessible(true);
                map = (Map<Identifier,AbstractTexture>) field.get(this);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
    }
    static Object invoke(String owner,String name,Class<?>[] types,Object...args){try{Method m=Class.forName(owner).getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(null,args);}catch(InvocationTargetException e){throw new AssertionError("native hook failure",e.getCause());}catch(Exception e){throw new AssertionError(e);}}
    static final Class<?>[] ALPHA={NativeImage.class,int.class,int.class,int.class,int.class};
    public static boolean strip(NativeImage i,int x,int y,int w,int h){
        CallbackInfo c=new CallbackInfo("probe-alpha",true);
        invoke("diy.y2k.five.thermite.mixin.MixinSkinTextureDownloader","stripAlpha",new Class[]{NativeImage.class,int.class,int.class,int.class,int.class,CallbackInfo.class},i,x,y,w,h,c);return c.isCancelled();
    }
    public static void reenterAlpha(NativeImage i,int x,int y,int w,int h){invoke("net.minecraft.client.renderer.texture.SkinTextureDownloader","setNoAlpha",ALPHA,i,x,y,w,h);}
    public static CompletableFuture<?> register(ClientAsset.Texture id,NativeImage image){
        CallbackInfoReturnable<CompletableFuture<?>> c=new CallbackInfoReturnable<>("probe-register",true);
        invoke("diy.y2k.five.thermite.mixin.MixinSkinTextureDownloader","registerTexture",new Class[]{ClientAsset.Texture.class,NativeImage.class,CallbackInfoReturnable.class},id,image,c);
        require(c.isCancelled()&&c.getReturnValue()!=null,"actual Ears HEAD registration callback");return c.getReturnValue();
    }
    static Identifier source=Identifier.fromNamespaceAndPath("nclskins","synthetic-ears");
    static long epoch;
    record Observation(Identifier id,AbstractTexture texture,long epoch) {
        boolean current(){if(mutation.equals("holder-only"))return texture instanceof FeaturesHolder h&&h.earsFeatures()!=null;return id.equals(source)&&epoch==EarsProbe.epoch&&Host.INSTANCE.textures.map.get(id)==texture&&texture instanceof FeaturesHolder h&&h.earsFeatures()!=null;}
    }
    static Observation observe(Identifier id){AbstractTexture t=Host.INSTANCE.textures.map.get(id);return t instanceof FeaturesHolder h&&h.earsFeatures()!=null ? new Observation(id,t,epoch):null;}
    public static RenderType material(Identifier id){try {RenderType t=(RenderType)U.allocateInstance(RenderType.class);materials.put(t,id);return t;}catch(Exception e){throw new AssertionError(e);}}
    static void require(boolean ok,String m){if(!ok)throw new AssertionError(m);}
    static <T>T raw(Class<T> c) throws Exception{return c.cast(U.allocateInstance(c));}
    static String mutation="";
    static EarsFeatures neutral(EarsFeatures f){return mutation.equals("t0-only")?EarsFeatures.copyOf(f).capeEnabled(false).build():EarsFeatures.copyOf(f).animateTail(false).wingAnimationMode(WingAnimationMode.NONE).capeEnabled(false).build();}
    static byte[] png(int w,int h)throws Exception{BufferedImage b=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<h;y++)for(int x=0;x<w;x++)b.setRGB(x,y,0xffbbaacc);ByteArrayOutputStream o=new ByteArrayOutputStream();ImageIO.write(b,"PNG",o);return o.toByteArray();}
    record Processed(IdentifierTextureRegistry registry,EarsTexture texture) implements AutoCloseable {public void close(){registry.close();}}
    public static byte[] writeFixture(Path staged, EarMode mode) throws Exception {
        return writeFixture(staged, mode, png(64,64));
    }
    public static byte[] writeFixture(Path staged, EarMode mode, byte[] base) throws Exception {
        AlfalfaData alfalfa=new AlfalfaData(1,Map.of("wing",new Slice(png(20,16)),"cape",new Slice(png(20,16))));
        EarsFeatures authored=EarsFeatures.builder().legMode(LegMode.PLANTIGRADE).protrusions(Protrusions.NONE).earMode(mode).earAnchor(EarAnchor.CENTER).tailMode(TailMode.DOWN).tailSegments(3).tailBends(25,15,-20,0).animateTail(true).wingMode(WingMode.SYMMETRIC_DUAL).wingAnimationMode(WingAnimationMode.NORMAL).capeEnabled(true).emissiveSkin(Slice.EMPTY).emissiveWing(Slice.EMPTY).displacedSkin(Slice.EMPTY).displacedEmissiveSkin(Slice.EMPTY).customLocatorSprites(List.of()).alfalfa(alfalfa).build();
        NativeImage image=NativeImage.read(base);
        EarsFeaturesWriterV1.write(authored,new NativeImageAdapter(image));
        image.writeToFile(staged);image.close();
        return Files.readAllBytes(staged);
    }
    static Processed fixture(EarMode mode) throws Exception {
        Path staged=Path.of(System.getProperty("probe.dir")).resolve("synthetic-"+mode+".png");
        byte[] bytes=writeFixture(staged,mode);String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        IdentifierTextureRegistry registry=new IdentifierTextureRegistry();
        var handle=registry.registerSkin(digest,bytes);source=Identifier.parse(handle.location());
        Runnable callback=Host.INSTANCE.queue.poll(15,TimeUnit.SECONDS);
        require(callback!=null,"native I/O producer reached held main-thread Ears registration");
        require((observe(source)==null||retainedPredecessor)&&NativePlayerSkinLifecycle.managedState(source.toString()).orElseThrow()==NativePlayerSkinLifecycle.State.PENDING,"NCL managed lifecycle pending and exact texture unprocessed");
        callback.run();
        require(NativePlayerSkinLifecycle.managedState(source.toString()).orElseThrow()==NativePlayerSkinLifecycle.State.READY,"actual NCL completion publishes READY after Ears processing");
        EarsTexture texture=(EarsTexture)Host.INSTANCE.textures.map.get(source);
        require(observe(source)!=null,"exact registered native processed texture ready");
        EarsFeatures f=texture.earsFeatures();
        require(f.enabled && f.wingMode==WingMode.SYMMETRIC_DUAL && f.tailSegments==3 && f.earMode==mode && f.capeEnabled,"real writer/preprocess/EarsTexture parser roundtrip");
        return new Processed(registry,texture);
    }
    public static final class Layer extends EarsLayerRenderer {
        final PlayerModel model;
        Layer(PlayerModel m){super(null);model=m;}
        @Override public PlayerModel getParentModel(){return model;}
    }
    public static final class State extends AvatarRenderState implements ExtendedPlayerRenderState {
        double capeX,capeY,capeZ;float horizontal,stride;boolean flying;
        public double ears$getCapeX(){return capeX;}public double ears$getCapeY(){return capeY;}public double ears$getCapeZ(){return capeZ;}
        public float ears$getHorizontalSpeed(){return horizontal;}public float ears$getStride(){return stride;}public boolean ears$isFlying(){return flying;}
        public <T extends net.minecraft.world.entity.Avatar & net.minecraft.client.entity.ClientAvatarEntity>void ears$update(T p,float t){throw new AssertionError("world update forbidden");}
    }
    static State state(float t)throws Exception{
        State s=raw(State.class);s.skin=new PlayerSkin(new ClientAsset.DownloadedTexture(source,"synthetic:no-network"),null,null,PlayerModelType.WIDE,false);s.ageInTicks=t;s.showHat=s.showJacket=s.showLeftSleeve=s.showRightSleeve=s.showLeftPants=s.showRightPants=true;return s;
    }
    static final class Sink implements VertexConsumer {
        final Set<String> materials=new TreeSet<>();
        final List<String> vertices=new ArrayList<>();float min=Float.POSITIVE_INFINITY,max=Float.NEGATIVE_INFINITY;
        String digest() throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join(";",vertices).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        public VertexConsumer addVertex(float x,float y,float z){require(Float.isFinite(x)&&Float.isFinite(y)&&Float.isFinite(z),"finite emitted vertices");min=Math.min(min,y);max=Math.max(max,y);vertices.add(x+","+y+","+z);return this;}
        public VertexConsumer setColor(int r,int g,int b,int a){return this;}public VertexConsumer setColor(int c){return this;}public VertexConsumer setUv(float u,float v){return this;}public VertexConsumer setUv1(int u,int v){return this;}public VertexConsumer setUv2(int u,int v){return this;}public VertexConsumer setUv3(float u,float v){return this;}public VertexConsumer setNormal(float x,float y,float z){return this;}public VertexConsumer setLineWidth(float w){return this;}
    }
    static Sink draw(Layer layer,EarsFeatures f,float t)throws Exception {return draw(layer,f,state(t));}
    static Sink draw(Layer layer,EarsFeatures f,State s)throws Exception {
        selected=f;drawingObservation=observe(source);Sink sink=new Sink();List<Runnable> pending=new ArrayList<>();
        SubmitNodeCollector q=(SubmitNodeCollector)Proxy.newProxyInstance(EarsProbe.class.getClassLoader(),new Class[]{SubmitNodeCollector.class},(self,m,a)->{
            if(m.getName().equals("order"))return self;
            if(m.getName().equals("submitCustomGeometry")){
                require(materials.containsKey((RenderType)a[1]),"real delegate acquired terminal material");
                SubmitNodeCollector.CustomGeometryRenderer callback=(SubmitNodeCollector.CustomGeometryRenderer)a[2];PoseStack.Pose pose=((PoseStack)a[0]).last().copy();Identifier material=materials.get((RenderType)a[1]);
                pending.add(()->{sink.materials.add(material.getPath().replace(source.getPath(),"SOURCE"));callback.render(pose,sink);});return null;
            }
            throw new AssertionError("excluded collector operation "+m);
        });
        PoseStack stack=new PoseStack();layer.submit(stack,q,0,s,0,0);
        require(sink.vertices.isEmpty()&&!pending.isEmpty(),"native producer really defers consumption");
        stack.translate(1000,1000,1000);selected=null;
        for(Runnable r:pending)r.run();require(!sink.vertices.isEmpty(),"deferred native callback emits actual vertices");
        System.out.println("DRAW t="+s.ageInTicks+" vertices="+sink.vertices.size()+" y=["+sink.min+","+sink.max+"] sha256="+sink.digest()+" materials="+sink.materials);
        return sink;
    }
    public static void main(String[] args)throws Exception {
        try { run(args); }
        finally {
            Host.INSTANCE.textures.close();
            require(Host.INSTANCE.queue.isEmpty(),"no queued native client work survives fixture teardown");
            System.out.println("PROBE_TEARDOWN_PASS noQueuedClientWork=true");
        }
    }
    private static void run(String[] args)throws Exception {
        mutation=args.length==0?"":args[0];
        if (mutation.equals("plain-cases")) { plainCases(); return; }
        if (mutation.equals("inline-cases")) { inlineCases(); return; }
        if (mutation.equals("native-cases")) { nativeCases(); return; }
        if (mutation.equals("same-id-aba") || mutation.equals("different-id-io")) {
            try (Processed seed = fixture(EarMode.TALL)) { }
            byte[] bytes = Files.readAllBytes(Path.of(System.getProperty("probe.dir")).resolve("synthetic-TALL.png"));
            String content = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            IdentifierTextureRegistry retiredRegistry = new IdentifierTextureRegistry();
            holdNextIo.set(true);
            var handle = retiredRegistry.registerSkin(content, bytes);
            Identifier retiredId = Identifier.parse(handle.location());
            require(oldIoEntered.await(15, TimeUnit.SECONDS), "actual old I/O producer reached gate before registration scheduling");
            require(Host.INSTANCE.queue.isEmpty(), "old main registration is not queued before replacement");
            CompletableFuture<?> retiredDownload = lastDownload;
            CompletableFuture<?> retiredFuture = lastTerminal;
            NativeImage retiredImage=lastIoImage;Path retiredPath=lastIoPath;
            require(Files.isRegularFile(retiredPath),"closed pending original I/O retains its own staged file until terminal");
            retiredRegistry.close();
            try (Processed replacement = fixture(mutation.equals("different-id-io")?EarMode.BEHIND:EarMode.TALL)) {
                require(source.equals(retiredId)==!mutation.equals("different-id-io"), "I/O gate same/different-ID discriminator");
                Observation current = observe(source);
                require(current.current() && Host.INSTANCE.textures.map.get(source) == replacement.texture(), "replacement processed texture is current before old callback");
                int creationsBefore=nativeCreations;
                releaseOldIo.countDown();
                CompletableFuture<?> oldFuture = retiredFuture;
                try { oldFuture.get(15, TimeUnit.SECONDS); } catch (ExecutionException expected) {
                    require(expected.getCause() instanceof java.util.concurrent.CancellationException, "retired I/O completes through cancellation");
                } catch (java.util.concurrent.CancellationException expected) { }
                require(retiredDownload.isDone()&&!Files.exists(retiredPath)&&closes(imageCloses,retiredImage)==1,"both original and terminal futures settle with exact-once own image/staging cleanup");
                require(nativeCreations==creationsBefore,"retired late I/O performs no native allocation");
                Runnable late;
                while ((late = Host.INSTANCE.queue.poll()) != null) late.run();
                require(Host.INSTANCE.textures.map.get(source) == replacement.texture() && current.current(), "retired same-ID completion must preserve replacement processed texture");
            } finally { releaseOldIo.countDown(); }
            require(networkAttempts == 0, "same-ID ABA performs no network attempt");
            System.out.println(mutation.equals("different-id-io")?"DIFFERENT_ID_IO_PASS":"SAME_ID_ABA_PASS");
            return;
        }
        ModelPart root=LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE,false),64,64).bakeRoot();Layer layer=new Layer(new PlayerModel(root,false));
        int completed=0;
        for(EarMode mode:EarMode.values()){
        try(Processed processed=fixture(mode)){
            EarsTexture texture=processed.texture();
            Observation current=observe(source);require(current.current(),"processed observation is current");
            Identifier original=source;source=Identifier.fromNamespaceAndPath("nclskins","source-replaced");
            require(!current.current(),"source replacement rejects old processed texture observation");source=original;
            epoch++;require(!current.current(),"resource reload rejects old processed texture observation");
            Observation reloaded=observe(source);require(reloaded.current(),"same registered processed texture can be observed under current epoch");
            Host.INSTANCE.textures.map.put(source,new DynamicTexture(()->"synthetic-unprocessed",NativeImage.read(png(64,64))));
            require(observe(source)==null&&!reloaded.current(),"same identifier replacement with raw texture is not Ears-ready");
            Host.INSTANCE.textures.map.get(source).close();Host.INSTANCE.textures.map.put(source,texture);
            require(observe(Identifier.fromNamespaceAndPath("nclskins","wrong"))==null,"wrong texture is not ready");
            EarsFeatures live=texture.earsFeatures();EarsFeatures n=neutral(live);
            State liveState=state(31);liveState.x=2;liveState.z=3;liveState.capeX=1;liveState.capeZ=2;liveState.bodyRot=28;liveState.walkAnimationSpeed=0.6f;
            Sink before=draw(layer,live,liveState);
            var modelBefore=root.getAllParts().stream().map(p->p.storePose().toString()+p.xScale+","+p.yScale+","+p.zScale+","+p.visible).toList();
            require(live.animateTail&&live.wingAnimationMode!=WingAnimationMode.NONE&&live.capeEnabled,"live features unchanged");
            Sink a=draw(layer,n,0),b=draw(layer,n,73);require(a.vertices.equals(b.vertices),"neutral authored geometry independent of time");
            require(n!=live && !n.animateTail && n.wingAnimationMode==WingAnimationMode.NONE && !n.capeEnabled,"neutral copy disables animations/cape without mutating live");
            require(a.materials.contains("SOURCE/ears/wing")&&!a.materials.contains("SOURCE/ears/cape")&&before.materials.contains("SOURCE/ears/cape"),"BODY wing material retained and managed native Ears cape excluded only in neutral pass");
            Sink zero=draw(layer,EarsFeatures.copyOf(live).capeEnabled(false).build(),0);require(!a.vertices.equals(zero.vertices),"t=0 animation is not neutral");
            Sink later=draw(layer,live,31),repeat=draw(layer,live,31);require(later.vertices.equals(repeat.vertices),"subsequent live render preserved");require(!draw(layer,live,80).vertices.equals(later.vertices),"live animation continues");
            require(n.earMode==live.earMode&&n.tailMode==live.tailMode&&n.tailSegments==live.tailSegments&&n.tailBend0==live.tailBend0&&n.tailBend1==live.tailBend1&&n.tailBend2==live.tailBend2&&n.wingMode==live.wingMode&&n.alfalfa==live.alfalfa,"authored BODY data and aux payload retained");
            Sink noWings=draw(layer,EarsFeatures.copyOf(n).wingMode(WingMode.NONE).build(),0);
            Sink noTail=draw(layer,EarsFeatures.copyOf(n).tailMode(TailMode.NONE).build(),0);
            require(!noWings.vertices.equals(a.vertices)&&!noTail.vertices.equals(a.vertices),"retained body wing and authored tail reach deferred vertices");
            if(mode!=EarMode.NONE)require(!draw(layer,EarsFeatures.copyOf(n).earMode(EarMode.NONE).build(),0).vertices.equals(a.vertices),"retained authored ears reach deferred vertices");
            if(mode==EarMode.TALL)require(!draw(layer,n,liveState).vertices.equals(a.vertices),"TALL ears depend on cape/entity displacement even with animations disabled");
            Sink after=draw(layer,live,liveState);require(before.vertices.equals(after.vertices),"same live state renders identically before and after isolated neutral pass");
            require(liveState.ageInTicks==31&&liveState.x==2&&liveState.z==3&&liveState.capeX==1&&liveState.capeZ==2&&liveState.bodyRot==28&&liveState.walkAnimationSpeed==0.6f,"live numeric motion untouched");
            require(modelBefore.equals(root.getAllParts().stream().map(p->p.storePose().toString()+p.xScale+","+p.yScale+","+p.zScale+","+p.visible).toList()),"native model anchor poses/scales/visibility unchanged");
            Sink ordinary=new Sink();root.render(new PoseStack(),ordinary,0,0);
            float min=Math.min(ordinary.min,a.min),max=Math.max(ordinary.max,a.max);
            System.out.println("CASE ear="+mode+" ordinaryHeight="+(ordinary.max-ordinary.min)+" unionHeight="+(max-min)+" unionCenterY="+((min+max)/2)+" neutralSha="+a.digest()+" t0Sha="+zero.digest()+" liveBeforeAfterSha="+before.digest()+" removedWingCount="+noWings.vertices.size()+" removedTailCount="+noTail.vertices.size()+" neutralMaterials="+a.materials+" liveMaterials="+before.materials);
            completed++;
        }
        require(observe(source)==null&&NativePlayerSkinLifecycle.registration(source.toString()).isEmpty(),"actual NCL close retires texture and lifecycle");
        }
        Observation retired;AbstractTexture old;
        try(Processed first=fixture(EarMode.TALL)){retired=observe(source);old=first.texture();}
        epoch++;
        try(Processed reprocessed=fixture(EarMode.TALL)){
            require(reprocessed.texture()!=old&&!retired.current()&&observe(source).current(),"reload reprocesses same exact identifier into new native texture; old observation cannot revive");
            draw(layer,neutral(reprocessed.texture().earsFeatures()),0);
            System.out.println("LIFECYCLE reload sameIdentifierNewTexture=true retiredObservation=false currentProcessed=true");
        }
        byte[] oldBytes=Files.readAllBytes(Path.of(System.getProperty("probe.dir")).resolve("synthetic-TALL.png"));
        IdentifierTextureRegistry retiredRegistry=new IdentifierTextureRegistry();
        var retiredHandle=retiredRegistry.registerSkin(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(oldBytes)),oldBytes);
        Identifier retiredId=Identifier.parse(retiredHandle.location());Runnable retiredCompletion=Host.INSTANCE.queue.poll(15,TimeUnit.SECONDS);require(retiredCompletion!=null,"held retired-source registration reached main lane");
        retiredRegistry.close();
        try(Processed newer=fixture(EarMode.ABOVE)){
            Observation newerObservation=observe(source);retiredCompletion.run();
            require(newerObservation.current()&&Host.INSTANCE.textures.map.get(source)==newer.texture(),"late completion for replaced different source preserves new processed texture");
            require(!Host.INSTANCE.textures.map.containsKey(retiredId)&&NativePlayerSkinLifecycle.registration(retiredId.toString()).isEmpty(),"actual retired NCL texture cleanup after late Ears processing");
            draw(layer,newer.texture().earsFeatures(),45);
            System.out.println("LIFECYCLE retiredBeforeRegistration oldTextureRemoved=true differentSourcePreserved=true liveAfter=true");
        }
        byte[] plain=png(64,64);IdentifierTextureRegistry ordinaryRegistry=new IdentifierTextureRegistry();
        var ordinaryHandle=ordinaryRegistry.registerSkin(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(plain)),plain);source=Identifier.parse(ordinaryHandle.location());
        Runnable ordinaryReady=Host.INSTANCE.queue.poll(15,TimeUnit.SECONDS);require(ordinaryReady!=null&&observe(source)==null,"ordinary texture pending is not processed disabled");ordinaryReady.run();
        Observation disabled=observe(source);require(disabled!=null&&disabled.current()&&!((FeaturesHolder)disabled.texture()).earsFeatures().enabled,"legitimately processed DISABLED is distinct from pending/wrong/raw texture");ordinaryRegistry.close();require(!disabled.current(),"closed processed disabled observation retired");
        System.out.println("LIFECYCLE processedDisabled=true pendingDistinct=true closeRetired=true");
        require(networkAttempts==0,"real local downloader branch performs no network attempt");
        System.out.println("EARS_NATIVE_COMPONENT_PASS cases="+completed+" networkAttempts="+networkAttempts+" not current NCL consumer or loader/GPU proof");
    }
}
