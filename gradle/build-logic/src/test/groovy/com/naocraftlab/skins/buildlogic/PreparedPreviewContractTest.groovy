package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import javax.tools.ToolProvider

import static org.junit.jupiter.api.Assertions.*

class PreparedPreviewContractTest {
    @TempDir File temporary

    @Test void renderedBufferInventoryIsReachedThroughTypedPublicWrapper() { verify('rendered-buffer') }
    @Test void meshInventoryIsReachedThroughTypedPublicWrapper() { verify('mesh') }
    @Test void extractionPublicationIsReachedThroughTypedPublicWrapper() { verify('extraction') }

    private void verify(String mode) {
        File source = new File('src/waveyLoaderComposition/contract/java/com/naocraftlab/skins/buildlogic/contract/PreparedPreviewContract.java')
        String cp = [ClassReader, ClassNode].collect {
            new File(it.protectionDomain.codeSource.location.toURI()).absolutePath
        }.unique().join(File.pathSeparator)
        def diagnostics = new ByteArrayOutputStream()
        assertEquals(0, ToolProvider.systemJavaCompiler.run(null, diagnostics, diagnostics,
                '-proc:none', '--release', '17', '-cp', cp, '-d', temporary.absolutePath, source.absolutePath), diagnostics.toString('UTF-8'))
        new URLClassLoader([temporary.toURI().toURL()] as URL[], getClass().classLoader).withCloseable { loader ->
            Class contract = loader.loadClass('com.naocraftlab.skins.buildlogic.contract.PreparedPreviewContract')
            String graphics = mode == 'extraction' ? 'GuiGraphicsExtractor' : 'GuiGraphics'
            String owner = mode == 'extraction' ? 'com/naocraftlab/skins/compat/client/identifier/CapeMaterialSnapshot$Carrier'
                    : 'net/minecraft/client/gui/screens/inventory/InventoryScreen'
            String name = mode == 'extraction' ? 'nclskins$setCapeMaterial' : 'renderEntityInInventory'
            String descriptor = (mode == 'extraction' ? '(Lcom/naocraftlab/skins/compat/client/identifier/CapeMaterialSnapshot;)V'
                    : '(Lnet/minecraft/client/gui/GuiGraphics;' + (mode == 'mesh' ? 'FFFLorg/joml/Vector3f;' : 'III')
                    + 'Lorg/joml/Quaternionf;Lorg/joml/Quaternionf;Lnet/minecraft/world/entity/LivingEntity;)V')
            int opcode = mode == 'extraction' ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKESTATIC
            boolean itf = mode == 'extraction'
            ClassNode node = new ClassNode()
            node.version = Opcodes.V17; node.access = Opcodes.ACC_PUBLIC
            node.name = 'com/naocraftlab/skins/fixture/PreparedPreview'; node.superName = 'java/lang/Object'
            String renderDescriptor = '(Lnet/minecraft/client/gui/' + graphics + ';Lcom/naocraftlab/skins/client/PreviewRenderer$PreviewRequest;)V'
            MethodNode entry = new MethodNode(Opcodes.ACC_PUBLIC, 'render', renderDescriptor, null, null)
            LabelNode second = new LabelNode()
            entry.instructions.add(new InsnNode(Opcodes.ICONST_0))
            entry.instructions.add(new JumpInsnNode(Opcodes.IFEQ, second))
            entry.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, 'renderPrepared', renderDescriptor, false))
            entry.instructions.add(new InsnNode(Opcodes.RETURN))
            entry.instructions.add(second)
            entry.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, 'renderPrepared', renderDescriptor, false))
            entry.instructions.add(new InsnNode(Opcodes.RETURN))
            MethodNode prepared = new MethodNode(Opcodes.ACC_PRIVATE, 'renderPrepared', renderDescriptor, null, null)
            prepared.instructions.add(new MethodInsnNode(opcode, owner, name, descriptor, itf))
            prepared.instructions.add(new InsnNode(Opcodes.RETURN))
            node.methods.add(entry); node.methods.add(prepared)
            contract.getMethod('verifyMutations', ClassNode, String, String, String, String, Integer.TYPE, Boolean.TYPE)
                    .invoke(null, node, graphics, owner, name, descriptor, opcode, itf)
        }
    }
}
