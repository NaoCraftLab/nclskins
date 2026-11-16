package com.naocraftlab.skins.buildlogic.probe;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
public final class PrepareAccess {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[1]);
        try (var cpm = new ZipFile(args[0])) {
            String resource = cpm.getEntry("cpm.accesswidener") != null ? "cpm.accesswidener" : "META-INF/accesstransformer.cfg";
            String rules = new String(cpm.getInputStream(cpm.getEntry(resource)).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            Files.createDirectories(out); Files.writeString(out.resolve("declared-access.txt"), rules);
            List<String[]> admitted = new ArrayList<>();
            for (String line : rules.split("\\R")) {
                String[] t = line.strip().split("\\s+");
                if (resource.endsWith("cfg")) {
                    if (t.length == 2 && Set.of("public", "public-f").contains(t[0])) {
                        admitted.add(new String[]{"accessible", "class", t[1].replace('.', '/')});
                        if (t[0].equals("public-f")) admitted.add(new String[]{"extendable", "class", t[1].replace('.', '/')});
                    }
                    if (t.length == 3 && Set.of("public", "public-f").contains(t[0])) {
                        admitted.add(new String[]{"accessible", "field", t[1].replace('.', '/'), t[2]});
                        if (t[0].equals("public-f")) admitted.add(new String[]{"mutable", "field", t[1].replace('.', '/'), t[2]});
                    }
                } else if (t.length >= 3 && Set.of("accessible", "mutable", "extendable").contains(t[0])) admitted.add(t);
            }
            Set<String> owners = new TreeSet<>();
            for (var t : admitted) if (t[2].startsWith("net/minecraft/client/model/")) owners.add(t[2]);
            for (String owner : owners) {
                var node = new ClassNode();
                try (var in = ClassLoader.getSystemResourceAsStream(owner + ".class")) {
                    if (in == null) throw new AssertionError("Missing declared SDK owner " + owner);
                    new ClassReader(in.readAllBytes()).accept(node, 0);
                }
                for (var t : admitted) if (t[2].equals(owner)) {
                    if (t[1].equals("class") && t[0].equals("extendable")) node.access &= ~Opcodes.ACC_FINAL;
                    if (t[1].equals("class") && t[0].equals("accessible")) node.access = (node.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
                    if (t[1].equals("field")) {
                        var matches = node.fields.stream().filter(f -> f.name.equals(t[3]) && (t.length == 4 || f.desc.equals(t[4]))).toList();
                        if (matches.size() != 1) throw new AssertionError("Ambiguous declared field " + Arrays.toString(t));
                        var field = matches.get(0);
                        if (t[0].equals("mutable")) field.access &= ~Opcodes.ACC_FINAL;
                        if (t[0].equals("accessible")) field.access = (field.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
                    }
                }
                var writer = new ClassWriter(0); node.accept(writer);
                var path = out.resolve(owner + ".class"); Files.createDirectories(path.getParent()); Files.write(path, writer.toByteArray());
                System.out.println("DECLARED_ACCESS " + owner);
            }
        }
    }
}
