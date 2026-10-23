package com.naocraftlab.skins.buildlogic.probe.wavey;
import java.nio.file.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
public final class BindCapeTerminals {
    public static void main(String[] a) throws Exception {
        try (ZipFile zip = new ZipFile(a[0])) {
            int edits=0;
            for (String owner : java.util.List.of("net/minecraft/client/renderer/rendertype/RenderType", "net/minecraft/client/renderer/rendertype/PreparedRenderType")) {
                ClassNode n = new ClassNode(); new ClassReader(zip.getInputStream(zip.getEntry(owner+".class")).readAllBytes()).accept(n, 0);
                edits += com.naocraftlab.skins.buildlogic.terminal.CapeTerminalBindings.apply(n, "prepared");
                ClassWriter w=new ClassWriter(0); n.accept(w); Path p=Path.of(a[1]).resolve(owner+".class"); Files.createDirectories(p.getParent()); Files.write(p,w.toByteArray());
            }
            if(edits!=2) throw new AssertionError("terminal binding="+edits);
            System.out.println("TWO_GPU_TERMINALS_BOUND; Group.prepare/merge/append and renderer build/execute/finish unchanged");
        }
    }
}
