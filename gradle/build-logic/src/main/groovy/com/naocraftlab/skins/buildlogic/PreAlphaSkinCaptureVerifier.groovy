package com.naocraftlab.skins.buildlogic

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

final class PreAlphaSkinCaptureVerifier {
    static boolean publicDuckLinks(byte[] interfaceBytes) {
        ClassReader reader = new ClassReader(interfaceBytes)
        int requiredType = Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT
        int forbiddenType = Opcodes.ACC_FINAL | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ENUM
        if ((reader.access & requiredType) != requiredType || (reader.access & forbiddenType) != 0
                || reader.superName != 'java/lang/Object' || reader.interfaces.length != 0) return false
        Map<String, String> requiredMethods = [
                'nclskins$rememberUnmodifiedSkinPixels': '([I)V',
                'nclskins$takeUnmodifiedSkinPixels': '()[I'
        ]
        Set<String> found = [] as Set<String>
        boolean[] valid = [true] as boolean[]
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            MethodVisitor visitMethod(int access, String name, String descriptor,
                                      String signature, String[] exceptions) {
                int required = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT
                int forbidden = Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC
                        | Opcodes.ACC_FINAL | Opcodes.ACC_NATIVE
                if (requiredMethods[name] != descriptor || (access & required) != required
                        || (access & forbidden) != 0 || !found.add(name)) valid[0] = false
                null
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        valid[0] && found == requiredMethods.keySet()
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
