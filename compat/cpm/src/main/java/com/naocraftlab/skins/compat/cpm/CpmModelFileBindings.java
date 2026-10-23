package com.naocraftlab.skins.compat.cpm;

import java.io.IOException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

public final class CpmModelFileBindings {
    private static final String ROOT = "com/tom/cpm/shared/";
    public static boolean supported(ClassLoader loader) {
        if (loader == null || loader.getResource("com/tom/cpm/shared/MinecraftClientAccess.class") == null) return false;
        try {
            return field(loader, "io/ModelFile", "link", "Lcom/tom/cpm/shared/definition/Link;")
                    && field(loader, "io/ModelFile", "overflowLocal", "[B")
                    && field(loader, "parts/ModelPartDefinition", "otherParts", "Ljava/util/List;")
                    && field(loader, "parts/ModelPartSkin", "image", "Lcom/tom/cpm/shared/skin/TextureProvider;")
                    && field(loader, "parts/ModelPartTexture", "texType", "Lcom/tom/cpm/shared/model/TextureSheetType;")
                    && field(loader, "parts/ModelPartTexture", "image", "Lcom/tom/cpm/shared/skin/TextureProvider;");
        } catch (IOException | RuntimeException failure) { return false; }
    }
    private static boolean field(ClassLoader loader, String owner, String name, String descriptor) throws IOException {
        try (var input = loader.getResourceAsStream(ROOT + owner + ".class")) {
            if (input == null) return false;
            var node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            var fields = node.fields.stream().filter(field -> field.name.equals(name)).toList();
            return node.name.equals(ROOT + owner) && fields.size() == 1 && fields.get(0).desc.equals(descriptor)
                    && (fields.get(0).access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC)) == Opcodes.ACC_PRIVATE;
        }
    }
}
