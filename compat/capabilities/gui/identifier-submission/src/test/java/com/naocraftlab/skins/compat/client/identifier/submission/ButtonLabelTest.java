package com.naocraftlab.skins.compat.client.identifier.submission;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class ButtonLabelTest {
    @Test
    void actionButtonUsesTheSameClippedScrollingLabelPathAsNativeButton() throws Exception {
        List<String> nativeCalls = renderCalls("/net/minecraft/client/gui/components/Button$Plain.class");
        List<String> actionCalls = renderCalls("NclSkinsScreen$ActionButton.class");
        for (String call : List.of("textRendererForWidget", "renderDefaultLabel")) {
            assertTrue(nativeCalls.contains(call), "Native button contract changed: " + call);
            assertTrue(actionCalls.contains(call), "Catalog button omits native label step: " + call);
        }
    }

    private static List<String> renderCalls(String resource) throws Exception {
        List<String> calls = new ArrayList<>();
        try (var stream = ButtonLabelTest.class.getResourceAsStream(resource)) {
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (!name.equals("renderContents")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String method,
                                                    String desc, boolean isInterface) {
                            calls.add(method);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return calls;
    }
}
