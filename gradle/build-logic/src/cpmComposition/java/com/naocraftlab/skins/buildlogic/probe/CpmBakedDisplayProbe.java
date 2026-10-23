package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.EditorPreviewSession;
import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import com.naocraftlab.skins.client.PreviewModelFrames;
import com.naocraftlab.skins.client.PreviewRenderer;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public final class CpmBakedDisplayProbe {
    private static Consumer<Object> enqueue;
    private static Runnable draw = () -> {};
    private static Runnable pop = () -> {};
    private static Runnable flushed = () -> {};
    private static Runnable lighting = () -> {};
    private static int draws;
    private static int poses;

    public static void main(String[] args) throws Exception {
        bodyProducer(new PreviewRenderer.PreviewAppearance(
                new com.naocraftlab.skins.client.TextureRegistry.TextureHandle("fixture:baked-display",64,64),
                com.naocraftlab.skins.client.SkinModel.CLASSIC, java.util.Optional.empty(),
                PreviewRenderer.CapeMode.OFF, com.naocraftlab.skins.client.OuterLayerVisibility.allVisible()));
    }

    static void bodyProducer(PreviewRenderer.PreviewAppearance appearance) throws Exception {
        String extraction = "com.naocraftlab.skins.compat.client.identifier.extraction.";
        String submission = "com.naocraftlab.skins.compat.client.identifier.submission.";
        boolean extracting = CpmBakedDisplayProbe.class.getClassLoader().getResource(extraction.replace('.', '/') + "AvatarPipPreviewRenderer.class") != null;
        String prefix = extracting ? extraction : submission;
        String bakedName = prefix + (extracting ? "AvatarPipPreviewRenderer" : "SimplePreviewRenderer");
        if (CpmBakedDisplayProbe.class.getClassLoader().getResource(bakedName.replace('.', '/') + ".class") == null) return;
        String mainName = prefix + (extracting ? "AvatarRenderStatePreviewRenderer" : "SubmissionPreviewRenderer");
        String dispatcherName = prefix + (extracting ? "NclBakedPlayerRenderer" : "BakedPreviewRenderer");
        String targetName = prefix + (extracting ? "NclBakedPlayerTarget" : "BakedPreviewTarget");
        String stateName = prefix + (extracting ? "NclBakedPlayerRenderState" : "BakedPreviewRenderState");
        String transportName = prefix + (extracting ? "NclBakedPlayerSubmission" : "BakedPreviewSubmission");
        var loader = new Loader(prefix, mainName, bakedName, dispatcherName, extracting);
        Client.current = new Client();
        var client = Client.current;
        Class<?> mainType = loader.loadClass(mainName);
        Class<?> bakedType = loader.loadClass(bakedName);
        Class<?> stateType = loader.loadClass(stateName);
        Class<?> targetType = loader.loadClass(targetName);
        Class<?> dispatcherType = loader.loadClass(dispatcherName);
        Class<?> transport = loader.loadClass(transportName);
        var replace = transport.getMethod("replace", Object.class, PipState.class);
        var render = mainType.getMethod("render", Graphics.class, PreviewRenderer.PreviewRequest.class);
        var nativePrepare = java.util.Arrays.stream(dispatcherType.getDeclaredMethods())
                .filter(m -> m.getName().equals("prepare") && !m.isBridge()).findFirst().orElseThrow();
        boolean late = nativePrepare.getParameterCount() == 4;
        var request = new PreviewRenderer.PreviewRequest(appearance, 0, 0, 160, 180, 0, 0, 1, PreviewRenderer.PreviewIntent.EDITOR_DRAFT);
        for (String scenario : java.util.List.of("worldless", "failed", "blocked", "timeout", "circuit", "cached-first", "draw-failure", "pose-failure", "late-failure", "pass-failure", "frame-failure", "retire", "closed", "no-texture", "target-closed")) {
            if (!late && java.util.Set.of("late-failure", "pass-failure", "frame-failure").contains(scenario)) continue;
            if (!loader.staticFlush && java.util.Set.of("pass-failure", "frame-failure").contains(scenario)) continue;
            long[] now = {0};
            var session = new EditorPreviewSession(() -> now[0]);
            boolean terminal = java.util.Set.of("failed", "blocked", "timeout", "circuit").contains(scenario);
            client.level = terminal ? new CpmPreviewScopeProbe.Level() : null;
            client.player = terminal ? new CpmPreviewScopeProbe.Avatar(client.level) : null;
            session.beginBody("fixture:baked", terminal, terminal);
            switch (scenario) {
                case "failed" -> session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.FAILED, () -> true));
                case "blocked" -> session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.BLOCKED, () -> true));
                case "timeout" -> { now[0] = java.time.Duration.ofSeconds(60).toNanos(); session.bodyStatus(); }
                case "circuit" -> session.disableLive(new IllegalStateException("synthetic circuit"));
                default -> {}
            }
            Object target = create(targetType);
            Object baked = create(bakedType);
            set(baked, "target", target);
            if (extracting) set(baked, "models", new Models());
            else { set(baked, "wide", new Model()); set(baked, "slim", new Model()); }
            Object main = create(mainType);
            set(main, "session", session);
            set(main, extracting ? "bakedRenderer" : "baked", baked);
            set(main, "diagnostics", com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
            set(main, "accessoryObserver", (Consumer<com.naocraftlab.skins.client.PreviewAccessories>) ignored -> {});
            if (scenario.equals("closed")) set(main, "closed", true);
            Object dispatcher = dispatcherType.getConstructors()[0].getParameterCount() == 0
                    ? create(dispatcherType) : dispatcherType.getConstructor(Buffers.class).newInstance(new Buffers());
            Object[] queued = {null};
            var graphics = new Graphics();
            enqueue = owner -> queued[0] = call(replace, null, owner, new VanillaState());
            var observed = new java.util.ArrayList<PreviewRenderer.DisplayKind>();
            PreviewRenderer<?> query = (PreviewRenderer<?>) main;
            session.observeBody(ignored -> {
                var kind = query.displayKind();
                if (kind == PreviewRenderer.DisplayKind.ORDINARY) check(poses == 0, "ordinary notification follows pose cleanup");
                observed.add(kind);
            });
            NativePlayerSkinLifecycle.Registration pending = scenario.equals("no-texture") ? NativePlayerSkinLifecycle.pending(appearance.skin().location()) : null;
            draws = 0; poses = 0;
            draw = () -> {
                check(query.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED, "primary draw alone is not completed display");
                if (scenario.equals("draw-failure")) throw new IllegalStateException("synthetic primary failure");
                if (scenario.equals("retire")) session.beginBody("fixture:replacement", false, false);
            };
            pop = () -> { if (scenario.equals("pose-failure")) throw new IllegalStateException("synthetic pose cleanup failure"); };
            CpmExtractionBodyProbe.bakedBoundaries(
                    () -> { check(query.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED, "late native flush precedes display commit"); if (scenario.equals("late-failure")) throw new IllegalStateException("synthetic late failure"); },
                    () -> { if (scenario.equals("pass-failure")) throw new IllegalStateException("synthetic pass cleanup failure"); },
                    () -> { if (scenario.equals("frame-failure")) throw new IllegalStateException("synthetic frame cleanup failure"); });
            CpmExtractionBodyProbe.cached = scenario.equals("cached-first");
            Throwable failure = null;
            try {
                call(render, main, graphics, request);
                check(query.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED, "actual main renderer enqueue is not display");
                if (queued[0] != null) {
                    var completion = stateType.getMethod("completion").invoke(queued[0]);
                    check(completion != null, "actual main fallback transports its scoped completion through withScissor");
                    if (scenario.equals("target-closed")) ((AutoCloseable) target).close();
                    prepare(nativePrepare, dispatcher, queued[0], late);
                }
            } catch (RuntimeException | Error thrown) { failure = thrown; }
            boolean positive = java.util.Set.of("worldless", "failed", "blocked", "timeout", "circuit").contains(scenario);
            check(!positive || failure == null, scenario + " must complete: " + failure);
            check(query.displayKind() == (positive ? PreviewRenderer.DisplayKind.ORDINARY : PreviewRenderer.DisplayKind.UNCONFIRMED), scenario + " actual baked display truth");
            if (positive) {
                check(draws == 1 && observed.stream().filter(k -> k == PreviewRenderer.DisplayKind.ORDINARY).count() == 1, "one primary draw and ordinary transition");
                int notifications = observed.size();
                Object previous = queued[0];
                CpmExtractionBodyProbe.cached = true;
                call(render, main, graphics, request);
                check(previous.equals(queued[0]), "unchanged authority preserves baked state/cache identity");
                prepare(nativePrepare, dispatcher, queued[0], late);
                check(draws == 1 && observed.size() == notifications, "cached unchanged pass does not draw or publish again");
                targetType.getMethod("release", Object.class).invoke(target, dispatcher);
                check(query.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED, "released target revokes ordinary proof");
                call(render, main, graphics, request);
                prepare(nativePrepare, dispatcher, queued[0], late);
                check(query.displayKind() == PreviewRenderer.DisplayKind.UNCONFIRMED, "cached/no-primary return cannot authorize replacement");
            } else if (java.util.Set.of("closed", "no-texture", "cached-first", "target-closed").contains(scenario)) {
                check(draws == 0, scenario + " must not execute a primary draw");
            }
            if (pending != null) pending.retire();
            ((AutoCloseable) target).close();
            ((AutoCloseable) dispatcher).close();
            System.out.println("PASS actual baked " + scenario + " late=" + late + " static=" + loader.staticFlush + " draws=" + draws);
        }
        enqueue = null; draw = () -> {}; pop = () -> {};
        CpmExtractionBodyProbe.bakedBoundaries(() -> {}, () -> {}, () -> {});
        CpmExtractionBodyProbe.cached = false;
        Client.current = null;
    }

    private static void prepare(java.lang.reflect.Method method, Object owner, Object state, boolean late) {
        call(method, owner, late ? new Object[]{state, new CpmExtractionBodyProbe.GuiRenderState(), new CpmExtractionBodyProbe.Features(), 1}
                : new Object[]{state, new CpmExtractionBodyProbe.GuiRenderState(), 1});
    }
    private static Object create(Class<?> type) throws Exception { var c = type.getDeclaredConstructor(); c.setAccessible(true); return c.newInstance(); }
    private static void set(Object owner, String name, Object value) throws Exception { var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner, value); }
    private static Object call(java.lang.reflect.Method method, Object owner, Object... args) {
        try { method.setAccessible(true); return method.invoke(owner, args); }
        catch (InvocationTargetException e) { if (e.getCause() instanceof RuntimeException r) throw r; if (e.getCause() instanceof Error r) throw r; throw new AssertionError(e.getCause()); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    static void primary() { draws++; draw.run(); }
    static void flush() { flushed.run(); }
    static void push() { poses++; }
    static void pop() { poses--; pop.run(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    static com.naocraftlab.skins.client.VanillaPlayerModelTransform.Operations<CpmPreviewScopeProbe.Pose> immediateOperations() {
        return new com.naocraftlab.skins.client.VanillaPlayerModelTransform.Operations<>() {
            public void scale(CpmPreviewScopeProbe.Pose pose,float x,float y,float z){pose.scale(x,y,z);}
            public void rotateZThenX(CpmPreviewScopeProbe.Pose pose,float z,float x){pose.mulPose(new org.joml.Quaternionf().rotateZ(z).rotateX(x));}
            public void rotateY(CpmPreviewScopeProbe.Pose pose,float y){pose.mulPose(new org.joml.Quaternionf().rotateY(y));}
            public void translate(CpmPreviewScopeProbe.Pose pose,float x,float y,float z){pose.translate(x,y,z);}
        };
    }

    static void immediateFallback(Class<?> type,Object renderer,CpmPreviewScopeProbe.Client client,
            PreviewRenderer.PreviewAppearance appearance) throws Exception {
        var render=type.getMethod("render",CpmPreviewScopeProbe.Graphics.class,PreviewRenderer.PreviewRequest.class);
        var request=new PreviewRenderer.PreviewRequest(appearance,0,0,160,180,0,0,1,PreviewRenderer.PreviewIntent.EDITOR_DRAFT);
        PreviewRenderer<?> query=(PreviewRenderer<?>) renderer;
        for(String scenario:java.util.List.of("worldless","failed","blocked","timeout","circuit","draw-failure","flush-failure","pose-failure","lighting-failure","retire","closed","no-texture")) {
            long[] now={0};var session=new EditorPreviewSession(()->now[0]);
            boolean terminal=java.util.Set.of("failed","blocked","timeout","circuit").contains(scenario);
            client.level=terminal?new CpmPreviewScopeProbe.Level():null;
            client.player=terminal?new CpmPreviewScopeProbe.Avatar(client.level):null;
            session.beginBody("fixture:immediate-baked",terminal,terminal);
            switch(scenario) {
                case "failed" -> session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.FAILED,()->true));
                case "blocked" -> session.body(new PreviewModelFrames.Body(PreviewRenderer.BodyStatus.BLOCKED,()->true));
                case "timeout" -> {now[0]=java.time.Duration.ofSeconds(60).toNanos();session.bodyStatus();}
                case "circuit" -> session.disableLive(new IllegalStateException("synthetic circuit"));
                default -> {}
            }
            set(renderer,"session",session);set(renderer,"closed",scenario.equals("closed"));
            int[] notifications={0};session.observeBody(ignored->{if(query.displayKind()==PreviewRenderer.DisplayKind.ORDINARY){check(poses==0,"ordinary follows pose cleanup");notifications[0]++;}});
            var pending=scenario.equals("no-texture")?NativePlayerSkinLifecycle.pending(appearance.skin().location()):null;
            draws=0;poses=0;
            draw=()->{check(query.displayKind()==PreviewRenderer.DisplayKind.UNCONFIRMED,"immediate primary is not completed display");if(scenario.equals("draw-failure"))throw new IllegalStateException("synthetic draw failure");if(scenario.equals("retire"))session.beginBody("fixture:replacement",false,false);};
            flushed=()->{check(query.displayKind()==PreviewRenderer.DisplayKind.UNCONFIRMED,"flush precedes display");if(scenario.equals("flush-failure"))throw new IllegalStateException("synthetic flush failure");};
            pop=()->{if(scenario.equals("pose-failure"))throw new IllegalStateException("synthetic pose cleanup");};
            lighting=()->{check(query.displayKind()==PreviewRenderer.DisplayKind.UNCONFIRMED,"lighting cleanup precedes completion");if(scenario.equals("lighting-failure"))throw new IllegalStateException("synthetic lighting cleanup");};
            Throwable failure=null;
            try {call(render,renderer,new CpmPreviewScopeProbe.Graphics(),request);}catch(RuntimeException|Error thrown){failure=thrown;}
            boolean positive=java.util.Set.of("worldless","failed","blocked","timeout","circuit").contains(scenario);
            check(!positive||failure==null,scenario+" immediate fallback completes: "+failure);
            check(query.displayKind()==(positive?PreviewRenderer.DisplayKind.ORDINARY:PreviewRenderer.DisplayKind.UNCONFIRMED),scenario+" immediate fallback display truth");
            if(positive) {
                check(draws==1&&notifications[0]==1,"one immediate primary and ordinary transition");
                draw=()->{};flushed=()->{};lighting=()->{};
                session.beginBody("fixture:immediate-baked",terminal,terminal);
                call(render,renderer,new CpmPreviewScopeProbe.Graphics(),request);
                check(notifications[0]==1,"unchanged immediate fallback does not publish again");
                var resource=NativePlayerSkinLifecycle.pending(appearance.skin().location());resource.ready();
                check(query.displayKind()==PreviewRenderer.DisplayKind.UNCONFIRMED,"new resource revokes immediate fallback");resource.retire();
            }
            if(pending!=null)pending.retire();
            System.out.println("PASS actual immediate baked "+scenario+" draws="+draws);
        }
        draw=()->{};pop=()->{};flushed=()->{};lighting=()->{};
    }

    private static final class Loader extends ClassLoader {
        private final String prefix, main, baked, dispatcher;
        private final Map<String, Class<?>> types = new HashMap<>();
        private boolean staticFlush;
        Loader(String prefix, String main, String baked, String dispatcher, boolean extracting) {
            super(CpmBakedDisplayProbe.class.getClassLoader()); this.prefix=prefix; this.main=main; this.baked=baked; this.dispatcher=dispatcher;
            types.put("net/minecraft/client/Minecraft", Client.class);
            types.put("net/minecraft/client/multiplayer/ClientLevel", CpmPreviewScopeProbe.Level.class);
            types.put("net/minecraft/client/player/LocalPlayer", CpmPreviewScopeProbe.Avatar.class);
            types.put("net/minecraft/client/player/AbstractClientPlayer", CpmPreviewScopeProbe.Avatar.class);
            types.put((main+"$PreviewPlayer").replace('.', '/'), CpmPreviewScopeProbe.Avatar.class);
            types.put("net/minecraft/world/entity/player/PlayerSkin", CpmExtractionBodyProbe.Skin.class);
            types.put("net/minecraft/world/item/ItemStack", CpmPreviewScopeProbe.Stack.class);
            types.put("net/minecraft/world/entity/Avatar", CpmPreviewScopeProbe.Player.class);
            types.put("net/minecraft/world/entity/LivingEntity", CpmPreviewScopeProbe.Entity.class);
            types.put("net/minecraft/client/gui/GuiGraphics", Graphics.class);
            types.put("net/minecraft/client/gui/GuiGraphicsExtractor", Graphics.class);
            for (String name : java.util.List.of("Model", "Model$Simple", "player/PlayerModel", "player/PlayerCapeModel", "object/equipment/ElytraModel")) types.put("net/minecraft/client/model/"+name, Model.class);
            types.put("net/minecraft/client/model/geom/ModelPart", Part.class);
            types.put("net/minecraft/resources/Identifier", Identifier.class);
            types.put("net/minecraft/client/gui/navigation/ScreenRectangle", Rectangle.class);
            types.put("net/minecraft/client/gui/render/state/pip/PictureInPictureRenderState", PipState.class);
            types.put("net/minecraft/client/renderer/state/gui/pip/PictureInPictureRenderState", PipState.class);
            types.put("net/minecraft/client/gui/render/state/GuiRenderState", CpmExtractionBodyProbe.GuiRenderState.class);
            types.put("net/minecraft/client/renderer/state/gui/GuiRenderState", CpmExtractionBodyProbe.GuiRenderState.class);
            types.put("net/minecraft/client/renderer/MultiBufferSource$BufferSource", Buffers.class);
            types.put("net/minecraft/client/renderer/MultiBufferSource", BufferAccess.class);
            types.put("net/minecraft/client/renderer/RenderType", RenderType.class);
            types.put("net/minecraft/client/renderer/rendertype/RenderType", RenderType.class);
            types.put("net/minecraft/client/renderer/rendertype/RenderTypes", RenderType.class);
            types.put("com/mojang/blaze3d/vertex/VertexConsumer", Vertex.class);
            types.put("com/mojang/blaze3d/vertex/PoseStack", CpmExtractionBodyProbe.Pose.class);
            types.put("com/mojang/blaze3d/platform/Lighting", CpmPreviewScopeProbe.Light.class);
            types.put("com/mojang/blaze3d/platform/Lighting$Entry", CpmPreviewScopeProbe.Light.Entry.class);
            types.put("net/minecraft/client/renderer/GameRenderer", GameRenderer.class);
            types.put("net/minecraft/client/renderer/SubmitNodeCollector", CpmExtractionBodyProbe.Collector.class);
            types.put("net/minecraft/client/renderer/OrderedSubmitNodeCollector", CpmExtractionBodyProbe.Collector.class);
            types.put("net/minecraft/client/renderer/SubmitNodeStorage", CpmExtractionBodyProbe.Storage.class);
            types.put("net/minecraft/client/renderer/feature/FeatureRenderDispatcher", CpmExtractionBodyProbe.Features.class);
            types.put("net/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame", CpmExtractionBodyProbe.PreparedFrame.class);
            types.put("net/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay", Overlay.class);
            types.put("net/minecraft/client/renderer/texture/OverlayTexture", Overlay.class);
            types.put("net/minecraft/util/Unit", Unit.class);
            types.put("com/mojang/blaze3d/systems/RenderSystem", CpmExtractionBodyProbe.RenderSystem.class);
            types.put("com/mojang/blaze3d/textures/GpuTexture", BakedTexture.class);
            types.put("com/mojang/blaze3d/textures/GpuTextureView", CpmExtractionBodyProbe.TextureView.class);
            types.put("com/mojang/renderpearl/api/textures/GpuTexture", CpmExtractionBodyProbe.GpuTexture.class);
            types.put("com/mojang/renderpearl/api/textures/GpuTextureView", CpmExtractionBodyProbe.TextureView.class);
            types.put("com/mojang/renderpearl/api/device/GpuDevice", CpmExtractionBodyProbe.Device.class);
            types.put("com/mojang/renderpearl/api/commands/CommandEncoder", CpmExtractionBodyProbe.Encoder.class);
            types.put("com/mojang/renderpearl/api/commands/RenderPass", CpmExtractionBodyProbe.Pass.class);
            types.put((prefix+"VanillaPreviewModels").replace('.', '/'), Models.class);
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            boolean pip = name.equals("net.minecraft.client.gui.render.pip.PictureInPictureRenderer");
            if (!pip && !name.startsWith(prefix)) return super.loadClass(name,resolve);
            Class<?> found=findLoadedClass(name);
            if (found==null) try (var input=getParent().getResourceAsStream(name.replace('.','/')+".class")) {
                if(input==null) throw new ClassNotFoundException(name);
                byte[] original=input.readAllBytes(); var node=new ClassNode(); new ClassReader(original).accept(node,0);
                if(pip) {
                    for(var m:node.methods) for(var ins:m.instructions) if(ins instanceof org.objectweb.asm.tree.MethodInsnNode c && c.name.equals("renderAllFeatures") && c.getOpcode()==Opcodes.INVOKESTATIC) staticFlush=true;
                    boolean deferred=node.methods.stream().anyMatch(m->m.name.equals("prepare") && m.desc.contains("FeatureRenderDispatcher"));
                    if(deferred) CpmExtractionBodyProbe.nativeTerminals(node,true);
                    else nativePlayerTerminals(node);
                    node.interfaces.add("java/lang/AutoCloseable");
                    var close=CpmExtractionBodyProbe.method(node,"close","()V");close.visitInsn(Opcodes.RETURN);close.visitMaxs(0,1);
                    for (var ctor : node.methods) if (ctor.name.equals("<init>") && ctor.desc.equals("()V")) {
                        var body = new org.objectweb.asm.tree.InsnList();
                        String texture = staticFlush ? "com/mojang/renderpearl/api/textures/GpuTexture" : "com/mojang/blaze3d/textures/GpuTexture";
                        String value = Type.getInternalName(staticFlush ? Gpu.class : BakedTexture.class);
                        body.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD,0));body.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW,value));body.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP));body.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESPECIAL,value,"<init>","()V",false));body.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTFIELD,node.name,"texture","L"+texture+";"));
                        if (deferred) {
                            String storage=Type.getInternalName(CpmExtractionBodyProbe.Storage.class);
                            body.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD,0));body.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW,storage));body.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP));body.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESPECIAL,storage,"<init>","()V",false));body.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTFIELD,node.name,"submitNodeStorage","Lnet/minecraft/client/renderer/SubmitNodeStorage;"));
                        }
                        ctor.instructions.insertBefore(ctor.instructions.getLast(),body);ctor.maxStack=3;
                    }
                    node.fields.add(new org.objectweb.asm.tree.FieldNode(Opcodes.ACC_PROTECTED,"bufferSource","Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;",null,null));
                    var ctor=new MethodNode(Opcodes.ACC_PUBLIC,"<init>","(Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;)V",null,null);
                    ctor.visitVarInsn(Opcodes.ALOAD,0); ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,node.name,"<init>","()V",false);
                    ctor.visitVarInsn(Opcodes.ALOAD,0); ctor.visitVarInsn(Opcodes.ALOAD,1); ctor.visitFieldInsn(Opcodes.PUTFIELD,node.name,"bufferSource","Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;"); ctor.visitInsn(Opcodes.RETURN);ctor.visitMaxs(2,2);node.methods.add(ctor);
                } else if(name.equals(main)) {
                    node.methods.removeIf(m -> !java.util.Set.of("render","body","prepareBody","observeBody","renderFallback","onLiveRenderFailure","clearAccessoryInspection").contains(m.name) && !m.name.startsWith("lambda$render"));
                    node.methods.removeIf(m -> (m.access & Opcodes.ACC_BRIDGE)!=0);
                    CpmExtractionBodyProbe.init(node);
                    node.fields.removeIf(f -> !java.util.Set.of("session","closed","bakedRenderer","baked","diagnostics","accessoryObserver").contains(f.name));
                    var method=CpmExtractionBodyProbe.method(node,"accessories","(Lcom/naocraftlab/skins/client/PreviewRenderer$PreviewRequest;)Lcom/naocraftlab/skins/client/PreviewAccessories;");
                    method.visitFieldInsn(Opcodes.GETSTATIC,"com/naocraftlab/skins/client/PreviewRenderer$LiveReason","LIVE_UNAVAILABLE","Lcom/naocraftlab/skins/client/PreviewRenderer$LiveReason;");
                    method.visitMethodInsn(Opcodes.INVOKESTATIC,"com/naocraftlab/skins/client/PreviewAccessories","unavailable","(Lcom/naocraftlab/skins/client/PreviewRenderer$LiveReason;)Lcom/naocraftlab/skins/client/PreviewAccessories;",false);method.visitInsn(Opcodes.ARETURN);method.visitMaxs(1,2);
                    node.innerClasses.clear();node.nestMembers=null;
                } else if(name.equals(baked)) {
                    CpmExtractionBodyProbe.init(node);
                }
                node.innerClasses.clear();
                var writer=new ClassWriter(0);node.accept(new ClassRemapper(writer,new Remapper(Opcodes.ASM9){ @Override public String map(String internal){var mapped=types.get(internal);return mapped==null?internal:Type.getInternalName(mapped);}}));
                byte[] bytes=writer.toByteArray();System.out.println("Baked class "+name+" original="+sha(original)+" headless="+sha(bytes));found=defineClass(name,bytes,0,bytes.length);
            } catch(java.io.IOException failure){throw new ClassNotFoundException(name,failure);}
            if(resolve)resolveClass(found);return found;
        }
        private void nativePlayerTerminals(ClassNode node) {
            node.access &= ~Opcodes.ACC_ABSTRACT; node.superName="java/lang/Object";node.signature=null;node.interfaces.clear();node.innerClasses.clear();node.nestMembers=null;
            node.methods.removeIf(m -> !m.name.equals("prepare"));node.fields.removeIf(f -> !java.util.Set.of("texture","textureView","depthTextureView").contains(f.name));CpmExtractionBodyProbe.init(node);
            String state=node.methods.stream().filter(m->m.name.equals("prepare")).findFirst().orElseThrow().desc.split(";")[0].substring(1)+";";
            var ready=CpmExtractionBodyProbe.method(node,"textureIsReadyToBlit","("+state+")Z");ready.visitFieldInsn(Opcodes.GETSTATIC,Type.getInternalName(CpmExtractionBodyProbe.class),"cached","Z");ready.visitInsn(Opcodes.IRETURN);ready.visitMaxs(1,2);
            var texture=CpmExtractionBodyProbe.method(node,"prepareTexturesAndProjection","(ZII)V");texture.visitInsn(Opcodes.RETURN);texture.visitMaxs(0,4);
            var blit=CpmExtractionBodyProbe.method(node,"blitTexture","("+state+"L"+(prefix.contains("submission")?"net/minecraft/client/gui/render/state/GuiRenderState":"net/minecraft/client/renderer/state/gui/GuiRenderState")+";)V");blit.visitInsn(Opcodes.RETURN);blit.visitMaxs(0,3);
            var y=CpmExtractionBodyProbe.method(node,"getTranslateY","(II)F");y.visitInsn(Opcodes.FCONST_0);y.visitInsn(Opcodes.FRETURN);y.visitMaxs(1,3);
            var render=CpmExtractionBodyProbe.method(node,"renderToTexture","("+state+"Lcom/mojang/blaze3d/vertex/PoseStack;)V");render.visitInsn(Opcodes.RETURN);render.visitMaxs(0,3);
        }
    }
    private static String sha(byte[] bytes) { try {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));} catch(Exception e){throw new AssertionError(e);} }
    public record Identifier(String value) { public static Identifier parse(String value){return new Identifier(value);} }
    public record Rectangle(int left,int top,int width,int height) {}
    public interface PipState {
        int x0();int x1();int y0();int y1();float scale();Rectangle scissorArea();
        static Rectangle getBounds(int x0,int y0,int x1,int y1,Rectangle scissor){return new Rectangle(x0,y0,x1-x0,y1-y0);}
    }
    public record VanillaState() implements PipState {public int x0(){return 0;}public int x1(){return 160;}public int y0(){return 0;}public int y1(){return 180;}public float scale(){return 1;}public Rectangle scissorArea(){return new Rectangle(0,0,160,180);}}
    public static final class Graphics {
        public void submitSkinRenderState(Model model,Identifier texture,float scale,float pitch,float yaw,float pivot,int x0,int y0,int x1,int y1){enqueue.accept(this);}
        public void skin(Model model,Identifier texture,float scale,float pitch,float yaw,float pivot,int x0,int y0,int x1,int y1){enqueue.accept(this);}
    }
    public static final class Client {
        static Client current; public static Client getInstance(){return current;}
        public CpmPreviewScopeProbe.Level level;public CpmPreviewScopeProbe.Avatar player;
        public final GameRenderer gameRenderer=new GameRenderer();
    }
    public static final class GameRenderer {public CpmPreviewScopeProbe.Light getLighting(){return new CpmPreviewScopeProbe.Light();}public CpmPreviewScopeProbe.Light lighting(){return getLighting();}}
    public static class Part {public boolean visible;public float xRot,yRot,zRot,x,y,z;public void resetPose(){}public Part getChild(String name){return new Part();} }
    public static class Model {
        public float attackTime;public boolean crouching,riding,young;
        public final Part head=new Part(),body=new Part(),rightArm=new Part(),leftArm=new Part(),rightLeg=new Part(),leftLeg=new Part();
        public final Part hat=new Part(),jacket=new Part(),leftSleeve=new Part(),rightSleeve=new Part(),leftPants=new Part(),rightPants=new Part();
        public void resetPose(){}public void setAllVisible(boolean visible){}
        public Part root(){return new Part();}
        public RenderType renderType(Identifier texture){return new RenderType();}
        public RenderType renderType(CpmPreviewScopeProbe.Location texture){return new RenderType();}
        public void renderToBuffer(CpmExtractionBodyProbe.Pose pose,Vertex consumer,int light,int overlay){primary();}
        public void renderToBuffer(CpmPreviewScopeProbe.Pose pose,Vertex consumer,int light,int overlay,int color){primary();}
        public void renderToBuffer(CpmPreviewScopeProbe.Pose pose,Vertex consumer,int light,int overlay,float r,float g,float b,float a){primary();}
    }
    public static final class Models {public final Model wideModel=new Model(),slimModel=new Model(),widePlayerModel=new Model(),slimPlayerModel=new Model();}
    public interface Vertex {}
    public interface BufferAccess {Vertex getBuffer(RenderType type);}
    public static final class Buffers implements BufferAccess {public Vertex getBuffer(RenderType type){return new Vertex(){};}public void endBatch(){flush();} }
    public static final class RenderType {public static RenderType entityTranslucent(Identifier id){return new RenderType();}public static RenderType entityTranslucent(CpmPreviewScopeProbe.Location id){return new RenderType();} }
    public static final class ImmediateLighting {
        public static void setupForEntityInInventory(){}
        public static void setupFor3DItems(){lighting.run();}
        public static void setShaderLights(org.joml.Vector3f first,org.joml.Vector3f second){}
    }
    public static final class Overlay { public static final int NO_OVERLAY=0; }
    public static final class BakedTexture { public int getWidth(int mip){return 160;}public int getHeight(int mip){return 180;} }
    public static final class Gpu implements CpmExtractionBodyProbe.GpuTexture { public int getWidth(int mip){return 160;}public int getHeight(int mip){return 180;} }
    public enum Unit {INSTANCE}
}
