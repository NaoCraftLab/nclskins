package com.naocraftlab.skins.probe;

import com.mojang.blaze3d.platform.NativeImage;
import com.naocraftlab.skins.client.*;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;

public final class QueuedUploadProbe {
    public static final class Host extends ReentrantBlockableEventLoop<Runnable> {
        static final Host INSTANCE = new Host();
        final Textures textures = new Textures();
        Host() { super("NCL local callback regression"); }
        public static Host getInstance() { return INSTANCE; }
        public Textures getTextureManager() { return textures; }
        protected Runnable wrapRunnable(Runnable r) { return r; }
        protected boolean shouldRun(Runnable r) { return true; }
        protected Thread getRunningThread() { return Thread.currentThread(); }
    }
    public static final class Textures {
        final Map<ResourceLocation, AbstractTexture> map = new HashMap<>();
        final IdentityHashMap<AbstractTexture, Integer> closes = new IdentityHashMap<>();
        public void register(ResourceLocation id, AbstractTexture texture) {
            try { texture.load(null); } catch (IOException e) { throw new UncheckedIOException(e); }
            AbstractTexture old = map.put(id, texture);
            if (old != null && old != texture) dispose(old);
        }
        void dispose(AbstractTexture texture) {
            closes.merge(texture, 1, Integer::sum);
            texture.close();
            texture.releaseId();
        }
        public void release(ResourceLocation id) { AbstractTexture old = map.remove(id); if (old != null) dispose(old); }
        public AbstractTexture getTexture(ResourceLocation id, AbstractTexture fallback) { return map.getOrDefault(id, fallback); }
        public ResourceLocation register(String id, DynamicTexture texture) { throw new AssertionError("Excluded IMAGE path"); }
    }
    public static final class Skins {
        public static ResourceLocation getDefaultSkin() { return ResourceLocation.tryParse("nclskins:synthetic-fallback"); }
        public static SkinValue get(UUID id) { return new SkinValue(); }
    }
    public static final class SkinValue { public ResourceLocation texture() { return Skins.getDefaultSkin(); } }
    public static final class Gpu {
        static int allocations, uploads, prepares, releases, fallbackLoads;
        static boolean deferRender;
        static final List<com.mojang.blaze3d.pipeline.RenderCall> renderCalls = new ArrayList<>();
        static final Set<Integer> live = new HashSet<>();
        static final IdentityHashMap<NativeImage, Integer> imageCloses = new IdentityHashMap<>();
        static final List<NativeImage> decoded = new ArrayList<>();
        public static boolean isOnRenderThread() { return !deferRender; }
        public static boolean isOnRenderThreadOrInit() { return true; }
        public static void assertOnRenderThreadOrInit() { }
        public static void assertOnRenderThread() { }
        public static void assertInInitPhase() { }
        public static int generateTextureId() {
            require(StackWalker.getInstance().walk(s -> s.anyMatch(f -> f.getClassName().equals(AbstractTexture.class.getName()) && f.getMethodName().equals("getId"))), "allocation must enter real AbstractTexture.getId");
            int id = ++allocations; live.add(id); return id;
        }
        public static void releaseTextureId(int id) { require(live.remove(id), "GPU ID released exactly once"); releases++; }
        public static void prepareImage(int id, int width, int height) { require(live.contains(id) && width == 64 && height == 64, "actual upload prepares captured image"); prepares++; }
        public static void uploaded(NativeImage image) {
            require(StackWalker.getInstance().walk(s -> s.anyMatch(f -> f.getClassName().equals(HttpTexture.class.getName()) && f.getMethodName().equals("upload"))), "GPU terminal must be called by actual native HttpTexture.upload");
            uploads++; image.close();
            System.out.println("ACTUAL_HTTP_UPLOAD allocations=" + allocations + " uploads=" + uploads);
        }
        public static void decoded(NativeImage image) { decoded.add(image); }
        public static void closed(NativeImage image) { imageCloses.merge(image, 1, Integer::sum); }
        public static void fallback(AbstractTexture texture) { fallbackLoads++; if (Boolean.getBoolean("probe.fallbackAllocates")) texture.getId(); }
        public static void recordRenderCall(com.mojang.blaze3d.pipeline.RenderCall call) { renderCalls.add(call); }
    }
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static String expectedLocation;
    static int originalPublications;
    static final Map<String, Object> published = new HashMap<>();
    static TextureRegistry registry(String owner) throws Exception {
        var constructor = Class.forName(owner).getDeclaredConstructor(String.class, Runnable.class);
        constructor.setAccessible(true);
        return (TextureRegistry) constructor.newInstance("preview", (Runnable) () -> {
            if (expectedLocation != null && NativePlayerSkinLifecycle.managedState(expectedLocation).orElse(null) == NativePlayerSkinLifecycle.State.READY)
                require(published.get(expectedLocation) == Host.INSTANCE.textures.map.get(ResourceLocation.tryParse(expectedLocation))
                        && published.get(expectedLocation) != null, "original pixels and manager admission must precede actual READY callback");
        });
    }
    static Path staged(AbstractTexture texture) throws Exception { var f = HttpTexture.class.getDeclaredField("file"); f.setAccessible(true); return ((File) f.get(texture)).toPath(); }
    static int nativeId(AbstractTexture texture) throws Exception { var f = AbstractTexture.class.getDeclaredField("id"); f.setAccessible(true); return f.getInt(texture); }
    static void drain() { while (Host.INSTANCE.pollTask()) { } }
    public static void main(String[] args) throws Exception {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream(); ImageIO.write(image, "PNG", output);
        byte[] bytes = output.toByteArray(); String digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        if (args[1].equals("vanilla")) {
            OwnedSkinFile file = OwnedSkinFile.stage(digest, bytes);
            ResourceLocation vanillaId = ResourceLocation.tryParse("nclskins:synthetic-fallback");
            HttpTexture texture = new HttpTexture(file.path().toFile(), "nclskins-local://fixture", vanillaId, true, () -> { });
            try {
                Host.INSTANCE.doRunTask(() -> Host.INSTANCE.textures.register(vanillaId, texture));
                require(Host.INSTANCE.getPendingTasksCount() == 2 && Gpu.decoded.size() == 1, "unmanaged actual local load remains queued");
                drain();
                require(Gpu.allocations == 1 && Gpu.uploads == 1 && Gpu.fallbackLoads == 1 && Gpu.imageCloses.get(Gpu.decoded.get(0)) == 1, "unmanaged texture preserves original callback/upload");
                Host.INSTANCE.textures.release(vanillaId);
                require(Gpu.live.isEmpty() && Gpu.releases == 1, "unmanaged native ID releases once");
                System.out.println("QUEUED_NATIVE_UPLOAD_PASS scenario=vanilla");
            } finally { Host.INSTANCE.textures.release(vanillaId); file.close(); System.out.println("QUEUED_NATIVE_TEARDOWN"); }
            return;
        }
        expectedLocation = "nclskins:preview/feature_skin/" + digest;
        var events = com.naocraftlab.skins.runtime.CapeProjection.installEvents(new com.naocraftlab.skins.runtime.CapeProjection.Events() {
            public void trackedPlayer(java.util.UUID id,String name) {} public void playerInfoUpdated(java.util.UUID id,String name) {} public void untrackedPlayer(java.util.UUID id) {} public void worldChanged() {} public void worldEntered() {}
            public void skinTextureReady(String location,int[] pixels,long revision) {
                var admitted = Host.INSTANCE.textures.map.get(ResourceLocation.tryParse(location));
                var selected = com.naocraftlab.skins.runtime.CapeProjection.effectiveSkin(new UUID(0, 812), new UUID(0, 813), location, SkinModel.CLASSIC, true);
                require(admitted instanceof HttpTexture && com.naocraftlab.skins.runtime.CapeProjection.ownsSkinTexture(selected, admitted), "legacy original pixels must not publish before exact manager admission");
                require(Arrays.equals(pixels, new int[4096]), "legacy admission publishes original rather than processed pixels");
                published.put(location, admitted);
                originalPublications++;
            }
        });
        TextureRegistry old = registry(args[0]); TextureRegistry replacement = registry(args[0]);
        AbstractTexture[] textures = new AbstractTexture[2]; Path[] paths = new Path[2]; ResourceLocation[] id = new ResourceLocation[1];
        String scenario = args[1];
        NativePlayerSkinLifecycle.Registration[] successor = new NativePlayerSkinLifecycle.Registration[1];
        int[] admittedAllocations = {0};
        try {
            if (Boolean.getBoolean("probe.inline")) {
                var handle = old.register(TextureRegistry.TextureKind.PLAYER_SKIN, digest, bytes);
                require(originalPublications == 1 && NativePlayerSkinLifecycle.isReady(handle.location()), "inline current raw identity reaches actual READY");
                System.out.println("LEGACY_INLINE_PUBLICATION_PASS");
                return;
            }
            Host.INSTANCE.doRunTask(() -> {
                try {
                    var handle = old.register(TextureRegistry.TextureKind.PLAYER_SKIN, digest, bytes);
                    id[0] = ResourceLocation.tryParse(handle.location());
                    textures[0] = Host.INSTANCE.textures.map.get(id[0]); paths[0] = staged(textures[0]);
                    require(Host.INSTANCE.getPendingTasksCount() == 2 && Gpu.decoded.size() == 1 && Gpu.allocations == 0, "real local loadCallback remains queued inside real running client task");
                    require(NativePlayerSkinLifecycle.managedState(id[0].toString()).orElseThrow() == NativePlayerSkinLifecycle.State.PENDING, "queued native skin starts PENDING");
                    if (scenario.equals("replacement") || scenario.equals("replacement-open")) {
                        replacement.register(TextureRegistry.TextureKind.PLAYER_SKIN, digest, bytes);
                        textures[1] = Host.INSTANCE.textures.map.get(id[0]); paths[1] = staged(textures[1]);
                        require(textures[0] != textures[1] && Host.INSTANCE.getPendingTasksCount() == 4, "same-ID replacement retains two distinct queued native callbacks");
                    }
                    if (scenario.equals("render-close")) Gpu.deferRender = true;
                    if (scenario.equals("allocated-close")) { textures[0].getId(); admittedAllocations[0] = 1; }
                    if (scenario.equals("stale-ticket")) successor[0] = NativePlayerSkinLifecycle.pending(id[0].toString());
                    if (scenario.equals("failed")) NativePlayerSkinLifecycle.registration(id[0].toString()).orElseThrow().failed();
                    if (scenario.equals("native-close")) Host.INSTANCE.textures.release(id[0]);
                    else if (!Set.of("positive", "replacement-open", "stale-ticket", "failed", "render-close").contains(scenario)) { old.close(); old.close(); }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            if (scenario.startsWith("replacement")) {
                Host.INSTANCE.pollTask(); Host.INSTANCE.pollTask();
                require(Gpu.allocations == 0 && Gpu.uploads == 0 && nativeId(textures[0]) == -1, "retired queued native HttpTexture must not allocate or upload");
                require(Gpu.imageCloses.getOrDefault(Gpu.decoded.get(0), 0) == 1, "retired queued image discarded exactly once");
                require(NativePlayerSkinLifecycle.managedState(id[0].toString()).orElseThrow() == NativePlayerSkinLifecycle.State.PENDING, "old callback leaves replacement PENDING");
                require(Host.INSTANCE.textures.map.get(id[0]) == textures[1], "old callback preserves exact replacement");
                old.close();
            }
            drain();
            if (scenario.equals("render-close")) {
                require(Gpu.renderCalls.size() == 1 && Gpu.uploads == 0 && Gpu.imageCloses.isEmpty(), "native callback leaves captured image queued on render lane");
                Gpu.deferRender = false;
                old.close(); old.close();
                Gpu.renderCalls.remove(0).execute();
            }
            boolean positive = scenario.equals("positive") || scenario.startsWith("replacement");
            if (positive) {
                require(Gpu.allocations == 1 && Gpu.uploads == 1 && Gpu.prepares == 1, "current local native callback still allocates and uploads once");
                require(NativePlayerSkinLifecycle.managedState(id[0].toString()).orElseThrow() == NativePlayerSkinLifecycle.State.READY, "actual successful upload publishes READY");
            } else require(Gpu.allocations == admittedAllocations[0] && Gpu.uploads == 0 && nativeId(textures[0]) == -1, "retired queued native HttpTexture must not allocate or upload");
            if (successor[0] != null) require(NativePlayerSkinLifecycle.registration(id[0].toString()).orElseThrow() == successor[0] && successor[0].state() == NativePlayerSkinLifecycle.State.PENDING, "stale ticket cannot complete current successor");
            for (NativeImage decoded : Gpu.decoded) require(Gpu.imageCloses.getOrDefault(decoded, 0) == 1, "each actual decoded native image closes once");
            old.close(); replacement.close(); old.close(); replacement.close();
            require(Gpu.live.isEmpty() && Gpu.allocations == Gpu.releases, "every allocated GPU ID released exactly once");
            require(Host.INSTANCE.textures.map.isEmpty() && Host.INSTANCE.getPendingTasksCount() == 0, "no map entry or queued work survives cleanup");
            for (int i = 0; i < textures.length; i++) if (textures[i] != null) {
                require(Host.INSTANCE.textures.closes.getOrDefault(textures[i], 0) == 1, "native object closed by manager exactly once");
                require(!Files.exists(paths[i]), "each owner releases its own actual staged local PNG");
            }
            System.out.println("QUEUED_NATIVE_UPLOAD_PASS scenario=" + scenario + " decoded=" + Gpu.decoded.size() + " allocations=" + Gpu.allocations + " uploads=" + Gpu.uploads + " releases=" + Gpu.releases + " fallbackLoads=" + Gpu.fallbackLoads);
        } finally {
            Gpu.deferRender = false;
            old.close(); replacement.close(); drain();
            for (var call : List.copyOf(Gpu.renderCalls)) call.execute();
            Gpu.renderCalls.clear();
            for (AbstractTexture texture : textures) if (texture != null) texture.releaseId();
            for (NativeImage decoded : Gpu.decoded) if (!Gpu.imageCloses.containsKey(decoded)) decoded.close();
            if (successor[0] != null) successor[0].retire();
            require(Host.INSTANCE.textures.map.isEmpty() && Host.INSTANCE.getPendingTasksCount() == 0
                    && Gpu.live.isEmpty(), "publication teardown leaves no current texture, queue or native ID");
            for (NativeImage decoded : Gpu.decoded)
                require(Gpu.imageCloses.getOrDefault(decoded, 0) == 1, "publication image closes exactly once");
            events.close();
            System.out.println("QUEUED_NATIVE_TEARDOWN");
        }
    }
}
