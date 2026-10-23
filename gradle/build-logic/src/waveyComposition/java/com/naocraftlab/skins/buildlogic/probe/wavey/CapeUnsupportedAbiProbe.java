package com.naocraftlab.skins.buildlogic.probe.wavey;

import java.io.File;
import java.net.*;
import java.util.*;
import java.nio.file.*;
import java.io.*;
import java.lang.reflect.*;
import java.lang.invoke.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class CapeUnsupportedAbiProbe {
    private static final String OWN = "com.naocraftlab.skins.compat.waveycapes.";
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--isolated")) {
            isolated(Path.of(args[1]));
            return;
        }
        List<URL> urls = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) urls.add(new File(entry).toURI().toURL());
        for (Class<?> type : List.of(CapeUnsupportedAbiProbe.class,
                com.naocraftlab.skins.compat.waveycapes.WaveyCapesIntegration.class,
                dev.tr7zw.waveycapes.render.CapeRenderer.class,
                dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper.class)) {
            URL origin = type.getProtectionDomain().getCodeSource().getLocation();
            if (!urls.contains(origin)) urls.add(origin);
        }
        try (HeadlessCapeClient client = HeadlessCapeClient.open(false)) {
            for (String absent : List.of("CapeRenderer:getCapePasses", "CapePass:wrapper")) {
                String[] missing = absent.split(":");
                try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), CapeUnsupportedAbiProbe.class.getClassLoader()) {
                    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                        synchronized (getClassLoadingLock(name)) {
                            if (!name.startsWith(OWN) && !name.startsWith("dev.tr7zw.")) return super.loadClass(name, resolve);
                            Class<?> type = findLoadedClass(name);
                            if (type == null && name.equals("dev.tr7zw.waveycapes.render." + missing[0])) {
                                try (var input = getResourceAsStream(name.replace('.', '/') + ".class")) {
                                    ClassNode node = new ClassNode(); new ClassReader(input.readAllBytes()).accept(node, 0);
                                    if (!node.methods.removeIf(method -> method.name.equals(missing[1])))
                                        throw new AssertionError("Unsupported ABI mutation did not bind");
                                    ClassWriter writer = new ClassWriter(0); node.accept(writer);
                                    byte[] bytes = writer.toByteArray();
                                    type = defineClass(name, bytes, 0, bytes.length);
                                } catch (java.io.IOException failure) { throw new ClassNotFoundException(name, failure); }
                            }
                            if (type == null) type = findClass(name);
                            if (resolve) resolveClass(type);
                            return type;
                        }
                    }
                }) {
                    Class<?> manager = Class.forName("dev.tr7zw.waveycapes.support.SupportManager", true, loader);
                    Set<?> registry = (Set<?>) manager.getMethod("getSupportedMods").invoke(null);
                    Set<?> before = new HashSet<>(registry);
                    Class<?> gate = Class.forName(OWN + "WaveyCapesIntegration", true, loader);
                    gate.getMethod("install", String.class).invoke(null, "1.11.3");
                    gate.getMethod("install", String.class).invoke(null, "1.11.3");
                    if (!registry.equals(before)) throw new AssertionError("Unsupported ABI changed genuine registry");
                    var registration = gate.getDeclaredField("registration"); registration.setAccessible(true);
                    if (registration.get(null) != null) throw new AssertionError("Unsupported ABI was accepted: " + absent);
                    gate.getMethod("close").invoke(null);
                    gate.getMethod("install", String.class).invoke(null, "1.11.3");
                    if (!registry.equals(before)) throw new AssertionError("Unsupported ABI recreated registration");
                }
            }
        }
        System.out.println("UNSUPPORTED_ABI_PASS real production optional gate; genuine API missing public pass method/accessor, no registration/recreation; test-only in-memory ABI mutations");
    }

    public static final String THREAD = "com/naocraftlab/skins/compat/waveycapes/CapeRenderThread";
    public static final String RENDERER = "dev/tr7zw/waveycapes/render/CapeRenderer";
    public static final String PASS = "dev/tr7zw/waveycapes/render/CapePass";
    public static final String PASSES = "(Ldev/tr7zw/transition/mc/entitywrapper/PlayerWrapper;)Ljava/util/List;";
    public static final String WRAPPER = "()Ljava/util/function/UnaryOperator;";
    private static final List<String> CELLS = List.of("supported", "missing-getCapePasses", "missing-wrapper");

    private static void check(boolean value, String oracle) {
        if (!value) throw new AssertionError(oracle);
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] entry(Path file, String name) throws Exception {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            var entry = zip.getEntry(name);
            if (entry == null) return null;
            try (var input = zip.getInputStream(entry)) { return input.readAllBytes(); }
        }
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node;
    }

    public static MethodNode select(ClassNode node, String owner, String name, String descriptor, boolean terminal) {
        if (!node.name.equals(owner)) throw new IllegalArgumentException("ABI_OWNER");
        var matches = node.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor)).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("ABI_EXACT_COUNT");
        MethodNode method = matches.get(0);
        if (terminal && (method.access != (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                || node.methods.stream().filter(candidate -> candidate.name.equals(name)).count() != 1))
            throw new IllegalArgumentException("ABI_TERMINAL_SHAPE");
        return method;
    }

    public static byte[] transform(byte[] bytes, String owner, String name, String descriptor, boolean terminal) {
        ClassNode node = node(bytes);
        MethodNode method = select(node, owner, name, descriptor, terminal);
        if (terminal) {
            check(owner.equals(THREAD) && name.equals("isClientThread") && descriptor.equals("()Z"), "ABI_TERMINAL_WHITELIST");
            method.instructions.clear(); method.tryCatchBlocks.clear();
            if (method.localVariables != null) method.localVariables.clear();
            method.instructions.add(new InsnNode(Opcodes.ICONST_1)); method.instructions.add(new InsnNode(Opcodes.IRETURN));
            method.maxStack = 1; method.maxLocals = 0;
        } else {
            check(owner.equals(RENDERER) && name.equals("getCapePasses") && descriptor.equals(PASSES)
                    || owner.equals(PASS) && name.equals("wrapper") && descriptor.equals(WRAPPER), "ABI_MUTATION_WHITELIST");
            node.methods.remove(method);
        }
        ClassWriter writer = new ClassWriter(0); node.accept(writer);
        byte[] result = writer.toByteArray();
        check(!Arrays.equals(bytes, result), "ABI_MUTATION_NO_EFFECT");
        return result;
    }

    public static void selectorControls(byte[] original) {
        select(node(original), THREAD, "isClientThread", "()Z", true);
        for (String mutation : List.of("wrong-descriptor", "duplicate", "missing", "overload", "shape", "owner")) {
            ClassNode changed = node(original);
            MethodNode method = select(changed, THREAD, "isClientThread", "()Z", true);
            switch (mutation) {
                case "wrong-descriptor" -> method.desc = "()I";
                case "duplicate" -> changed.methods.add(node(original).methods.stream().filter(m -> m.name.equals("isClientThread")).findFirst().orElseThrow());
                case "missing" -> changed.methods.remove(method);
                case "overload" -> changed.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "isClientThread", "(I)Z", null, null));
                case "shape" -> method.access = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC;
                case "owner" -> changed.name += "Other";
            }
            try { select(changed, THREAD, "isClientThread", "()Z", true); }
            catch (IllegalArgumentException expected) { continue; }
            throw new AssertionError("ABI_SELECTOR_FAIL_OPEN " + mutation);
        }
    }

    private static final class Cell extends ClassLoader implements AutoCloseable {
        private final Path production, sdk;
        private final String variant;
        private final Map<String, byte[]> nested = new HashMap<>();
        private final Map<String, String> origins = new HashMap<>();
        Cell(Path production, Path sdk, String variant) throws Exception {
            super(CapeUnsupportedAbiProbe.class.getClassLoader());
            this.production = production; this.sdk = sdk; this.variant = variant;
            try (ZipFile zip = new ZipFile(sdk.toFile())) {
                for (ZipEntry e : Collections.list(zip.entries())) if (e.getName().endsWith(".jar")) {
                    byte[] carrier;
                    try (var input = zip.getInputStream(e)) { carrier = input.readAllBytes(); }
                    System.out.println("ABI_NESTED " + variant + " " + e.getName() + " " + hash(carrier));
                    try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(carrier))) {
                        for (ZipEntry x; (x = input.getNextEntry()) != null;) if (x.getName().endsWith(".class")) {
                            check(nested.put(x.getName(), input.readAllBytes()) == null, "ABI_DUPLICATE_NESTED");
                            origins.put(x.getName(), e.getName());
                        }
                    }
                }
            }
            check(origins.values().stream().anyMatch(n -> n.contains("TRansition"))
                    && origins.values().stream().anyMatch(n -> n.contains("TRender")), "ABI_MISSING_GENUINE_COMPANIONS");
        }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!(name.startsWith("com.naocraftlab.skins.") || name.startsWith("dev.tr7zw."))) return super.loadClass(name, resolve);
            Class<?> type = findLoadedClass(name);
            if (type == null) try {
                String resource = name.replace('.', '/') + ".class";
                boolean own = name.startsWith("com.naocraftlab.skins.");
                byte[] original = entry(own ? production : sdk, resource);
                check(original == null || !nested.containsKey(resource), "ABI_AMBIGUOUS_DEFINITION");
                String origin = own ? "production" : "sdk";
                if (original == null && !own) { original = nested.get(resource); origin = "nested:" + origins.get(resource); }
                if (original == null) throw new ClassNotFoundException(name);
                byte[] chosen = original;
                if (resource.equals(THREAD + ".class")) {
                    selectorControls(original);
                    chosen = transform(original, THREAD, "isClientThread", "()Z", true);
                    System.out.println("ABI_TERMINAL " + variant + " " + THREAD + " isClientThread ()Z 1 " + hash(original) + " " + hash(chosen));
                }
                if (variant.equals("missing-getCapePasses") && resource.equals(RENDERER + ".class")
                        || variant.equals("missing-wrapper") && resource.equals(PASS + ".class")) {
                    boolean renderer = resource.equals(RENDERER + ".class");
                    String owner = renderer ? RENDERER : PASS, member = renderer ? "getCapePasses" : "wrapper", descriptor = renderer ? PASSES : WRAPPER;
                    chosen = transform(original, owner, member, descriptor, false);
                    System.out.println("ABI_MUTATION " + variant + " " + owner + " " + member + " " + descriptor + " 1 " + hash(original) + " " + hash(chosen));
                }
                System.out.println("ABI_DEFINE " + variant + " " + name + " " + origin + " " + hash(original) + " " + hash(chosen));
                type = defineClass(name, chosen, 0, chosen.length);
            } catch (ClassNotFoundException failure) { throw failure; }
            catch (Exception failure) { throw new ClassNotFoundException(name, failure); }
            if (resolve) resolveClass(type);
            return type;
        }
        @Override public void close() { nested.clear(); origins.clear(); }
    }

    private static void missingMember(Cell loader, String variant) throws Exception {
        if (variant.equals("supported")) return;
        boolean wrapper = variant.equals("missing-wrapper");
        Class<?> owner = Class.forName((wrapper ? PASS : RENDERER).replace('/', '.'), false, loader);
        MethodType descriptor = wrapper ? MethodType.methodType(java.util.function.UnaryOperator.class)
                : MethodType.methodType(List.class, Class.forName("dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper", false, loader));
        try { MethodHandles.publicLookup().findVirtual(owner, wrapper ? "wrapper" : "getCapePasses", descriptor); }
        catch (NoSuchMethodException expected) { System.out.println("ABI_MISSING_MEMBER_PASS " + variant); return; }
        throw new AssertionError("ABI_MEMBER_STILL_EXISTS");
    }

    private static void productionLinkage(Cell loader, String variant) throws Exception {
        if (variant.equals("supported")) return;
        try { Class.forName(OWN + "WaveyCapesSupport", true, loader).getMethod("register").invoke(null); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            String member = variant.equals("missing-wrapper") ? "dev.tr7zw.waveycapes.render.CapePass.wrapper()"
                    : "dev.tr7zw.waveycapes.render.CapeRenderer.getCapePasses(dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper)";
            check(cause instanceof NoSuchMethodError && cause.getMessage().contains(member)
                    && Arrays.stream(cause.getStackTrace()).anyMatch(frame -> frame.getClassName().equals(OWN + "WaveyCapesSupport")
                    && frame.getMethodName().equals("register")), "ABI_NOT_PRODUCTION_MISSING_MEMBER");
            System.out.println("ABI_PRODUCTION_LINKAGE_PASS " + variant + " NoSuchMethodError " + member);
            return;
        }
        throw new AssertionError("ABI_PRODUCTION_REGISTER_ACCEPTED");
    }

    @SuppressWarnings("unchecked")
    private static void cell(Path production, Path sdk, String version, String variant) throws Exception {
        try (Cell loader = new Cell(production, sdk, variant)) {
            Class<?> manager = Class.forName("dev.tr7zw.waveycapes.support.SupportManager", true, loader);
            Set<Object> registry = (Set<Object>) manager.getMethod("getSupportedMods").invoke(null);
            List<Object> initial = new ArrayList<>(registry);
            Class<?> support = Class.forName("dev.tr7zw.waveycapes.support.ModSupport", false, loader);
            Object foreign = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{support}, (proxy, method, args) -> switch (method.getName()) {
                case "priority" -> -100;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "ABI foreign sentinel";
                default -> method.getReturnType() == boolean.class ? false : null;
            });
            Class<?> gate = Class.forName(OWN + "WaveyCapesIntegration", true, loader);
            Method install = gate.getMethod("install", String.class), close = gate.getMethod("close");
            Field registration = gate.getDeclaredField("registration"); registration.setAccessible(true);
            check(registry.add(foreign), "ABI_FOREIGN_ADD");
            List<Object> before = new ArrayList<>(registry);
            try {
                missingMember(loader, variant);
                install.invoke(null, version);
                Object own = registration.get(null);
                if (variant.equals("supported")) {
                    check(own != null && own.getClass().getName().equals(OWN + "WaveyCapesSupport")
                            && own.getClass().getClassLoader() == loader, "ABI_SUPPORTED_ACTUAL_OWN");
                    check(registry.size() == before.size() + 1 && registry.contains(own) && registry.containsAll(before), "ABI_SUPPORTED_REGISTRY_EFFECT");
                    check((int) own.getClass().getMethod("priority").invoke(own) == 1, "ABI_PRIORITY");
                } else {
                    check(own == null && new ArrayList<>(registry).equals(before), "ABI_NEGATIVE_PARTIAL_MUTATION");
                    productionLinkage(loader, variant);
                    check(new ArrayList<>(registry).equals(before), "ABI_LINKAGE_PARTIAL_MUTATION");
                }
                install.invoke(null, version);
                check(registration.get(null) == own && registry.size() == before.size() + (own == null ? 0 : 1), "ABI_REPEAT_IDENTITY");
                close.invoke(null); close.invoke(null);
                check(registration.get(null) == null && new ArrayList<>(registry).equals(before), "ABI_CLOSE_ONLY_OWN");
                install.invoke(null, version);
                check(registration.get(null) == null && new ArrayList<>(registry).equals(before), "ABI_CLOSED_NO_RECREATE");
                check(manager.getMethod("getSupportedMods").invoke(null) == registry, "ABI_REGISTRY_IDENTITY");
                System.out.println("ABI_CELL_PASS " + variant);
            } finally {
                close.invoke(null);
                check(registry.remove(foreign) && new ArrayList<>(registry).equals(initial), "ABI_REGISTRY_RESTORE");
                System.out.println("ABI_CELL_CLEANUP_PASS " + variant);
            }
        }
        System.out.println("ABI_CELL_CLOSED " + variant);
    }

    private static void isolated(Path bindingsFile) throws Exception {
        check(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .noneMatch(argument -> argument.startsWith("-javaagent") || argument.startsWith("--add-exports") || argument.startsWith("--add-opens")), "ABI_FORBIDDEN_VM_BYPASS");
        Properties bindings = new Properties();
        try (var input = Files.newBufferedReader(bindingsFile)) { bindings.load(input); }
        Path production = Path.of(bindings.getProperty("production.path")), sdk = Path.of(bindings.getProperty("sdk.path"));
        check(hash(Files.readAllBytes(production)).equals(bindings.getProperty("production.sha256"))
                && hash(Files.readAllBytes(sdk)).equals(bindings.getProperty("sdk.sha256")), "ABI_STALE_ARTIFACT");
        System.out.println("ABI_BINDING_PASS " + bindings.getProperty("binding"));
        for (String variant : CELLS) cell(production, sdk, bindings.getProperty("version"), variant);
        System.out.println("ISOLATED_PRODUCTION_ABI_CLOSED_PASS");
    }
}
