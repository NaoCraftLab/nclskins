package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import java.util.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import com.naocraftlab.skins.compat.cpm.CpmModelFileBindings;

public final class CpmModelMetadataTransformProbe {
    private static void shapes() throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        var plugin = new com.naocraftlab.skins.compat.cpm.CpmMixinPlugin();
        for (String[] field : new String[][]{{"parts/ModelPartDefinition","otherParts"},{"parts/ModelPartSkin","image"},{"parts/ModelPartTexture","texType"},{"parts/ModelPartTexture","image"},{"io/ModelFile","link"},{"io/ModelFile","overflowLocal"}})
        for (String mode : List.of("missing","descriptor","static","duplicate")) {
            String resource = "com/tom/cpm/shared/" + field[0] + ".class";
            var node = new ClassNode();
            try (var in = original.getResourceAsStream(resource)) { new ClassReader(in).accept(node,0); }
            var target = node.fields.stream().filter(f -> f.name.equals(field[1])).findFirst().orElseThrow();
            switch (mode) {
                case "missing" -> node.fields.remove(target);
                case "descriptor" -> target.desc = "Ljava/lang/Object;";
                case "static" -> target.access |= Opcodes.ACC_STATIC;
                case "duplicate" -> node.fields.add(new FieldNode(target.access,target.name,target.desc,target.signature,target.value));
            }
            var writer = new ClassWriter(0); node.accept(writer); byte[] changed = writer.toByteArray();
            var loader = new ClassLoader(original) {
                @Override public java.io.InputStream getResourceAsStream(String name) { return name.equals(resource) ? new java.io.ByteArrayInputStream(changed) : super.getResourceAsStream(name); }
            };
            if (CpmModelFileBindings.supported(loader)) throw new AssertionError("Shape drift admitted " + Arrays.toString(field) + mode);
            Thread.currentThread().setContextClassLoader(loader);
            try { if (plugin.shouldApplyMixin("unused","CpmModelTextureAccessor")) throw new AssertionError("Plugin admitted drift"); }
            finally { Thread.currentThread().setContextClassLoader(original); }
            System.out.println("PASS exact field readiness " + Arrays.toString(field) + " " + mode);
        }
        if (!CpmModelFileBindings.supported(original)) throw new AssertionError("Healthy binding counter-control rejected");
        var absent = new ClassLoader(original) {
            @Override public java.net.URL getResource(String name) { return name.startsWith("com/tom/") ? null : super.getResource(name); }
            @Override public java.io.InputStream getResourceAsStream(String name) { return name.startsWith("com/tom/") ? null : super.getResourceAsStream(name); }
            @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException { if(name.startsWith("com.tom."))throw new AssertionError("Optional readiness loaded native type");return super.loadClass(name,resolve); }
        };
        if(CpmModelFileBindings.supported(absent))throw new AssertionError("Absent native supported");
        Thread.currentThread().setContextClassLoader(absent);
        try { if(plugin.shouldApplyMixin("unused","CpmModelDefinitionPartsAccessor"))throw new AssertionError("Absent plugin activation"); }
        finally { Thread.currentThread().setContextClassLoader(original); }
        System.out.println("PASS healthy six-field/absent no-native-classloading counter-controls");
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 1 && args[1].equals("shapes")) { shapes(); return; }
        boolean server = args.length > 1 && args[1].equals("server");
        Path output = Path.of(args[0]);
        var required = List.of("CpmModelDefinitionPartsAccessor", "CpmModelSkinTextureAccessor", "CpmModelTextureAccessor", "CpmModelFileBackupAccessor");
        try (var in = ClassLoader.getSystemResourceAsStream("nclskins.cpm.mixins.json")) {
            var config = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in)).getAsJsonObject();
            for (String name : required) if (!config.getAsJsonArray("client").contains(new com.google.gson.JsonPrimitive(name))) throw new AssertionError("Missing production registration " + name);
        }
        if (!CpmModelFileBindings.supported(Thread.currentThread().getContextClassLoader())) throw new AssertionError("Original exact six fields unavailable");
        MixinBootstrap.init();
        var phase = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
        phase.setAccessible(true); phase.invoke(null, MixinEnvironment.Phase.DEFAULT);
        var env = MixinEnvironment.getDefaultEnvironment(); env.setSide(server ? MixinEnvironment.Side.SERVER : MixinEnvironment.Side.CLIENT);
        var ctor = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor(); ctor.setAccessible(true); ctor.newInstance();
        Mixins.addConfiguration("metadata-binding.json");
        var transformer = (IMixinTransformer) env.getActiveTransformer();
        var owners = List.of("parts.ModelPartDefinition", "parts.ModelPartSkin", "parts.ModelPartTexture", "io.ModelFile");
        for (int i = 0; i < owners.size(); i++) {
            String name = "com.tom.cpm.shared." + owners.get(i), entry = name.replace('.', '/') + ".class";
            var origin = ClassLoader.getSystemResource(entry);
            byte[] before; try (var in = origin.openStream()) { before = in.readAllBytes(); }
            byte[] after = transformer.transformClass(env, name, before);
            var node = new ClassNode(); new ClassReader(after).accept(node, 0);
            if (server) {
                if (!Arrays.equals(before, after)) throw new AssertionError("Client accessor transformed server class");
                System.out.println("PASS server client-only isolation " + name); continue;
            }
            if (!node.interfaces.contains("com/naocraftlab/skins/compat/cpm/mixin/" + required.get(i))) throw new AssertionError("Actual production accessor not applied");
            Path file = output.resolve(entry); Files.createDirectories(file.getParent()); Files.write(file, after);
            System.out.println("TRANSFORM " + name + " ORIGIN " + origin + " " + before.length + " -> " + after.length);
        }
    }
}
