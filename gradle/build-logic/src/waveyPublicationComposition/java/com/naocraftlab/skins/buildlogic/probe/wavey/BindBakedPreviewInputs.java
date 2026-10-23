package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.naocraftlab.skins.buildlogic.terminal.BakedPreviewBindings;
import com.naocraftlab.skins.buildlogic.contract.BakedPreviewContract;
import java.util.*;
import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;

public final class BindBakedPreviewInputs {
    public static void main(String[] args) throws Exception {
        BakedPreviewContract.verifyNativeInputs(Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))).map(Path::of).toList());
        int changed = 0;
        for (String owner : BakedPreviewBindings.OWNERS) {
            ClassNode node = new ClassNode();
            try (var input = ClassLoader.getSystemResourceAsStream(owner + ".class")) { new ClassReader(input.readAllBytes()).accept(node, 0); }
            changed += BakedPreviewBindings.apply(node);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer);
            Path output = Path.of(args[0]).resolve(owner + ".class"); Files.createDirectories(output.getParent()); Files.write(output, writer.toByteArray());
        }
        if (changed != 2) throw new AssertionError("BAKED_PUBLIC_BINDER_COUNT");
        System.out.println("BAKED_PUBLIC_EXACT_TERMINALS=2; same pure binder as canonical CLIENT SPI; publication/native consumers retained");
    }
}
