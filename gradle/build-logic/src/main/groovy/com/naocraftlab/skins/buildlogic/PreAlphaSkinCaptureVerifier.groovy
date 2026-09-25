package com.naocraftlab.skins.buildlogic

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

final class PreAlphaSkinCaptureVerifier {
    static boolean publicDuckLinks(byte[] interfaceBytes) {
        ClassReader reader = new ClassReader(interfaceBytes)
        String interfaceName = reader.className
        String probeName = 'com/naocraftlab/skins/linkage/NativeImageDuckProbe'
        ClassWriter writer = new ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                probeName, null, 'java/lang/Object', [interfaceName] as String[])
        writer.visitEnd()
        byte[] probeBytes = writer.toByteArray()
        ClassLoader loader = new ClassLoader(PreAlphaSkinCaptureVerifier.class.classLoader) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name == interfaceName.replace('/', '.')) {
                    return defineClass(name, interfaceBytes, 0, interfaceBytes.length)
                }
                if (name == probeName.replace('/', '.')) {
                    return defineClass(name, probeBytes, 0, probeBytes.length)
                }
                throw new ClassNotFoundException(name)
            }
        }
        try {
            Class<?> probe = loader.loadClass(probeName.replace('/', '.'))
            return probe.interfaces.length == 1 && probe.interfaces[0].name == interfaceName.replace('/', '.')
        } catch (LinkageError | ClassNotFoundException rejected) {
            return false
        }
    }

    static boolean injectedAtHead(byte[] bytes, String handler, String targetMethod) {
        int[] handlers = [0] as int[]
        boolean[] method = [false] as boolean[]
        boolean[] head = [false] as boolean[]
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            MethodVisitor visitMethod(int access, String name, String methodDescriptor,
                                      String signature, String[] exceptions) {
                if (name != handler) return null
                handlers[0]++
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        if (annotation != 'Lorg/spongepowered/asm/mixin/injection/Inject;') return null
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            AnnotationVisitor visitArray(String key) {
                                if (key == 'method') return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    void visit(String ignored, Object value) {
                                        if (value == targetMethod) method[0] = true
                                    }
                                }
                                if (key == 'at') return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    AnnotationVisitor visitAnnotation(String ignored, String atType) {
                                        if (atType != 'Lorg/spongepowered/asm/mixin/injection/At;') return null
                                        return new AnnotationVisitor(Opcodes.ASM9) {
                                            @Override
                                            void visit(String field, Object value) {
                                                if (field == 'value' && value == 'HEAD') head[0] = true
                                            }
                                        }
                                    }
                                }
                                null
                            }
                        }
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        handlers[0] == 1 && method[0] && head[0]
    }

    static boolean modernStages(byte[] bytes) {
        Map<String, Set<String>> calls = [:].withDefault { [] as Set<String> }
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            MethodVisitor visitMethod(int access, String name, String methodDescriptor,
                                      String signature, String[] exceptions) {
                if (!(name in ['nclskins$rememberUnmodifiedSkinPixels', 'nclskins$captureSkinPixels'])) return null
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    void visitMethodInsn(int opcode, String owner, String called,
                                         String calledDescriptor, boolean isInterface) {
                        calls[name].add(owner + '.' + called)
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        String carrier = 'com/naocraftlab/skins/compat/client/identifier/SneakyUnmodifiedSkinPixels.'
        String projection = 'com/naocraftlab/skins/runtime/CapeProjection.skinTextureReady'
        Set<String> before = calls['nclskins$rememberUnmodifiedSkinPixels']
        Set<String> after = calls['nclskins$captureSkinPixels']
        before.contains(carrier + 'nclskins$rememberUnmodifiedSkinPixels')
                && !before.contains(projection)
                && after.contains(carrier + 'nclskins$takeUnmodifiedSkinPixels')
                && after.contains(projection)
                && !after.contains(carrier + 'nclskins$rememberUnmodifiedSkinPixels')
    }
}
