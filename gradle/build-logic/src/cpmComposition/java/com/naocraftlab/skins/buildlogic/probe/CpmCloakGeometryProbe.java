package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.runtime.CapeProjection;
import com.naocraftlab.skins.runtime.EffectiveSkinSource;
import com.naocraftlab.skins.core.provider.BuiltinProvider;
import com.tom.cpl.math.Vec4f;
import com.tom.cpl.render.VBuffers;
import com.tom.cpl.render.VertexBuffer;
import com.tom.cpm.shared.animation.AnimationState;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.RootModelType;
import com.tom.cpm.shared.model.render.ModelRenderManager;
import com.tom.cpm.shared.model.render.RenderMode;
import com.tom.cpm.shared.model.render.VanillaModelPart;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

public final class CpmCloakGeometryProbe {
    public static void run(CapeProjection.Registration registration, Player<?> player,
            EffectiveSkinSource source, ModelDefinition definition) throws Exception {
        String owner = ClassLoader.getSystemResource("com/tom/cpm/client/PlayerRenderManager$RedirectModelRendererBase.class") != null
                ? "com.tom.cpm.client.PlayerRenderManager$RedirectModelRendererBase"
                : "com.tom.cpm.client.PlayerRenderManager$RedirectModelRenderer";
        Method handler = consumerHandler(owner);
        var manager = new ModelRenderManager<Object, Object, Object, Object>() {};
        manager.setModelSetters((part, x, y, z) -> {}, (part, x, y, z) -> {});
        manager.setModelPosGetters(part -> 0, part -> 0, part -> 0);
        manager.setModelRotGetters(part -> 0, part -> 0, part -> 0);
        manager.setVis(part -> true, (part, visible) -> {});
        manager.setRedirectFactory((value, access, parent, part) -> new GeometryRenderer(access, parent.get(), part, handler));
        var parts = new ModelParts();
        var holder = new ModelRenderManager.RedirectHolder<Object, Object, Object, Object>(manager, parts) {
            {
                register(new ModelRenderManager.Field<>(() -> parts.cloak, value -> parts.cloak = value, RootModelType.CAPE));
                register(new ModelRenderManager.Field<>(() -> parts.body, value -> parts.body = value, PlayerModelParts.BODY));
            }
            protected void swapIn0() {}
            protected void swapOut0() {}
        };
        manager.setFactory(new ModelRenderManager.RedirectHolderFactory<Object, Object, Object>() {
            public <M> ModelRenderManager.RedirectHolder<?, Object, Object, Object> create(M value, String arg) {
                return holder;
            }
        });
        for (RenderMode mode : RenderMode.values()) holder.renderTypes.put(mode, new VBuffers.NativeRenderType(0));
        holder.sheetX = holder.sheetY = 64;
        var animation = new AnimationState();
        manager.bindModel(parts, null, definition, player, animation);
        holder.preRenderSetup = holder.skinBound = true;
        var cloak = (GeometryRenderer) parts.cloak;
        var body = (GeometryRenderer) parts.body;
        Object capeRoot = definition.getModelElementFor(RootModelType.CAPE);
        Object bodyRoot = definition.getModelElementFor(PlayerModelParts.BODY);
        Object animations = definition.getAnimations();
        try {
            CpmAccessoryProbe.select(registration, player, source, definition, true);
            var nativeCape = cloak.geometry();
            var nativeBody = body.geometry();
            check(!nativeCape.isEmpty() && !nativeCape.equals(cloak.parentGeometry()), "distinct CPM cloak vertices");
            check(!nativeBody.isEmpty(), "BODY emits geometry");
            for (String selection : List.of("ordinary", "disabled", "cpm")) {
                boolean winner = selection.equals("cpm");
                CpmAccessoryProbe.select(registration, player, source, definition, winner);
                if (selection.equals("disabled")) {
                    registration.publish(new CapeProjection.Snapshot(List.of(BuiltinProvider.MINECRAFT),
                            Map.of(), Map.of(), Map.of(), Map.of(),
                            Map.of(new CapeProjection.Identity(source.cacheId(), player.getName()), CapeProjection.cpmCandidate(source)),
                            null, null, null));
                }
                CpmAccessoryProbe.cape(definition, player, winner);
                var actual = cloak.geometry();
                check(actual.equals(winner ? nativeCape : cloak.parentGeometry()),
                        "redirected cloak fall-through must emit ordinary geometry when CPM loses");
                check(body.geometry().equals(nativeBody), "BODY vertex stream unchanged across cape selection");
                check(manager.isBound(parts) && parts.cloak == cloak && parts.body == body
                        && holder.def == definition && holder.playerObj == player
                        && definition.getModelElementFor(RootModelType.CAPE) == capeRoot
                        && definition.getModelElementFor(PlayerModelParts.BODY) == bodyRoot
                        && definition.getAnimations() == animations,
                        "same body binding, definition roots and animation owner");
            }
            CpmAccessoryProbe.select(registration, player, source, definition, false);
            holder.playerObj = null;
            check(cloak.geometry().equals(nativeCape), "unowned renderer preserves upstream cape geometry");
            holder.playerObj = player;
            System.out.println("Cloak vertices: CPM=" + nativeCape.size() + ", ordinary=" + cloak.parentGeometry().size()
                    + "; unchanged BODY=" + nativeBody.size());
        } finally {
            manager.unbindModel(parts);
        }
        System.out.println("Redirected cloak geometry: upstream render emits ordinary/CPM vertices; BODY vertices and binding unchanged");
    }

    private record Point(float x, float y, float z) {}

    private static Method consumerHandler(String owner) throws Exception {
        var node = new ClassNode();
        try (var input = ClassLoader.getSystemResourceAsStream(owner.replace('.', '/') + ".class")) {
            new ClassReader(input).accept(node, 0);
        }
        var handlers = node.methods.stream().filter(method -> method.name.endsWith("$nclskins$accessoryPart")).toList();
        check(handlers.size() <= 1, "consumer part handler cardinality");
        if (handlers.isEmpty()) return null;
        var handler = handlers.get(0);
        check((handler.access & Opcodes.ACC_STATIC) != 0 && handler.desc.equals(
                "(Ljava/lang/Object;Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Lcom/tom/cpm/shared/model/render/VanillaModelPart;"),
                "exact transformed consumer handler descriptor");
        String name = "com.naocraftlab.skins.buildlogic.probe.CpmCloakConsumer";
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null);
        handler.accept(writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "select", handler.desc, null, null));
        writer.visitEnd();
        var bridge = new ClassLoader(CpmCloakGeometryProbe.class.getClassLoader()) {
            Class<?> define() { byte[] bytes = writer.toByteArray(); return defineClass(name, bytes, 0, bytes.length); }
        }.define();
        return bridge.getMethod("select", Object.class, Operation.class);
    }

    private static final class ModelParts {
        Object cloak = new Object();
        Object body = new Object();
    }

    private static final class GeometryRenderer implements ModelRenderManager.RedirectRenderer<Object>, VertexBuffer {
        private final ModelRenderManager.RedirectHolder<?, ?, ?, Object> holder;
        private final VanillaModelPart part;
        private final Method handler;
        private final Object parent;
        private final List<Point> vertices = new ArrayList<>();
        private Point position;

        private GeometryRenderer(ModelRenderManager.RedirectHolder<?, ?, ?, Object> holder, Object parent,
                VanillaModelPart part, Method handler) {
            this.holder = holder;
            this.parent = parent;
            this.part = part;
            this.handler = handler;
        }

        private List<Point> geometry() {
            vertices.clear();
            render();
            return List.copyOf(vertices);
        }

        private List<Point> parentGeometry() {
            return List.of(new Point(-0.3F, 0, 0), new Point(0.3F, 0, 0),
                    new Point(0.3F, 1, 0), new Point(-0.3F, 1, 0));
        }

        public Object swapIn() { return this; }
        public Object swapOut() { return parent; }
        public ModelRenderManager.RedirectHolder<?, ?, ?, Object> getHolder() { return holder; }
        public Object getParent() { return parent; }
        public VanillaModelPart getPart() {
            if (handler == null) return part;
            Operation<VanillaModelPart> field = values -> part;
            try { return (VanillaModelPart) handler.invoke(null, this, field); }
            catch (ReflectiveOperationException failure) { throw new AssertionError("executing transformed native consumer", failure); }
        }
        public void renderParent() { vertices.addAll(parentGeometry()); }
        public VBuffers getVBuffers() { return new VBuffers(type -> this).setBatched(true); }
        public Vec4f getColor() { return new Vec4f(1, 1, 1, 1); }
        public VertexBuffer pos(float x, float y, float z) { position = new Point(x, y, z); return this; }
        public VertexBuffer tex(float u, float v) { return this; }
        public VertexBuffer color(float r, float g, float b, float a) { return this; }
        public VertexBuffer normal(float x, float y, float z) { return this; }
        public void endVertex() { vertices.add(position); }
        public void finish() {}
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
