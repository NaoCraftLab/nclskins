package com.naocraftlab.skins.buildlogic

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

final class WaveyCapesAbiVerifier {
    private static final String RENDERER = 'dev/tr7zw/waveycapes/render/VanillaCapeRenderer.class'
    private static final String WRAPPER = 'dev/tr7zw/transition/mc/entitywrapper/PlayerWrapper.class'
    private static final String UTIL = 'dev/tr7zw/transition/mc/PlayerUtil.class'

    static void verify(Map pinned, File directory) {
        (pinned.artifacts as Map).each { Object targetId, Object raw ->
            Map artifact = raw as Map
            File file = new File(directory, artifact.file.toString())
            if (!file.isFile() || digest(file.bytes) != artifact.sha512) {
                throw new IllegalStateException("WaveyCapes ${targetId} public artifact SHA-512 mismatch")
            }
            new ZipFile(file).withCloseable { ZipFile zip ->
                Set<String> rendererCalls = calls(read(zip, RENDERER), 'getCapeInfo')
                if (!rendererCalls.any { it.contains('PlayerWrapper.getCapeTexture()') }
                        || !rendererCalls.any { it.startsWith(artifact.renderFactory.toString()) }
                        || !returnsTrue(read(zip, RENDERER), 'vanillaUvValues')) {
                    throw new IllegalStateException("WaveyCapes ${targetId} no longer consumes a translucent vanilla-UV cape texture")
                }
                String nestedName = zip.entries().collect { it.name }.find {
                    it.startsWith('META-INF/jars/TRansition-') ||
                            it.startsWith('META-INF/jarjar/TRansition-')
                }
                if (nestedName == null) {
                    throw new IllegalStateException("WaveyCapes ${targetId} has no bundled transition ABI")
                }
                Map<String, byte[]> nested = nestedClasses(read(zip, nestedName), [WRAPPER, UTIL] as Set)
                if (!nested.keySet().containsAll([WRAPPER, UTIL])) {
                    throw new IllegalStateException("WaveyCapes ${targetId} transition ABI is incomplete")
                }
                if (!calls(nested[WRAPPER], 'getCapeTexture').any {
                    it.contains('PlayerUtil.getPlayerCape(')
                }) {
                    throw new IllegalStateException("WaveyCapes ${targetId} wrapper does not query player cape")
                }
                Set<String> vanillaCalls = calls(nested[UTIL], 'getPlayerCape')
                if (!vanillaCalls.any { it.startsWith(artifact.capeSource.toString()) }) {
                    throw new IllegalStateException("WaveyCapes ${targetId} vanilla cape source changed")
                }
            }
        }
    }

    private static boolean returnsTrue(byte[] bytes, String method) {
        boolean[] constant = [false] as boolean[]
        boolean[] returned = [false] as boolean[]
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            MethodVisitor visitMethod(int access, String name, String descriptor,
                                      String signature, String[] exceptions) {
                if (name != method || descriptor != '()Z') return null
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    void visitInsn(int opcode) {
                        if (opcode == Opcodes.ICONST_1) constant[0] = true
                        if (opcode == Opcodes.IRETURN) returned[0] = true
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        constant[0] && returned[0]
    }

    private static Set<String> calls(byte[] bytes, String method) {
        Set<String> result = [] as Set
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            MethodVisitor visitMethod(int access, String name, String descriptor,
                                      String signature, String[] exceptions) {
                if (name != method) return null
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    void visitMethodInsn(int opcode, String owner, String called,
                                         String calledDescriptor, boolean isInterface) {
                        result.add(owner + '.' + called + calledDescriptor)
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        result
    }

    private static Map<String, byte[]> nestedClasses(byte[] jar, Set<String> required) {
        Map<String, byte[]> classes = [:]
        new ZipInputStream(new ByteArrayInputStream(jar)).withCloseable { ZipInputStream zip ->
            def entry
            while ((entry = zip.nextEntry) != null) {
                if (required.contains(entry.name)) classes[entry.name] = zip.readAllBytes()
            }
        }
        classes
    }

    private static byte[] read(ZipFile zip, String name) {
        def entry = zip.getEntry(name)
        if (entry == null) throw new IllegalStateException("Missing WaveyCapes entry ${name}")
        zip.getInputStream(entry).withCloseable { it.readAllBytes() }
    }

    private static String digest(byte[] bytes) {
        MessageDigest.getInstance('SHA-512').digest(bytes).encodeHex().toString()
    }
}
