package com.naocraftlab.skins.buildlogic;

import org.objectweb.asm.*;
import org.objectweb.asm.signature.*;
import java.util.*;

public final class CompiledDependencies {
    public record Edge(String source, String target, String location, String memberName, String memberDescriptor) {
        public Edge(String source, String target, String location) { this(source, target, location, null, null); }
    }
    public record Scan(String name, List<Edge> edges) {}

    public static Scan read(byte[] bytes) {
        List<Edge> edges = new ArrayList<>();
        String source = new ClassReader(bytes).getClassName();
        class References {
            void name(String name, String at) {
                if (name == null) return;
                if (name.startsWith("[")) descriptor(name, at);
                else edges.add(new Edge(source, name, at));
            }
            void member(String owner, String name, String descriptor, String at) {
                edges.add(new Edge(source, owner, at, name, descriptor));
                descriptor(descriptor, at + " descriptor");
            }
            void type(Type t, String at) {
                switch (t.getSort()) {
                    case Type.ARRAY -> type(t.getElementType(), at);
                    case Type.OBJECT -> name(t.getInternalName(), at);
                    case Type.METHOD -> {
                        type(t.getReturnType(), at);
                        for (Type p : t.getArgumentTypes()) type(p, at);
                    }
                }
            }
            void descriptor(String d, String at) { if (d != null) type(Type.getType(d), at); }
            void signature(String s, String at, boolean field) {
                if (s == null) return;
                SignatureVisitor visitor = signatureVisitor(at);
                if (field) new SignatureReader(s).acceptType(visitor);
                else new SignatureReader(s).accept(visitor);
            }
            SignatureVisitor signatureVisitor(String at) {
                return new SignatureVisitor(Opcodes.ASM9) {
                    String outer;
                    public void visitClassType(String n) { outer = n; name(n, at); }
                    public void visitInnerClassType(String n) { outer += "$" + n; name(outer, at); }
                    public SignatureVisitor visitTypeArgument(char wildcard) { return signatureVisitor(at); }
                    public SignatureVisitor visitArrayType() { return signatureVisitor(at); }
                    public SignatureVisitor visitClassBound() { return signatureVisitor(at); }
                    public SignatureVisitor visitInterfaceBound() { return signatureVisitor(at); }
                    public SignatureVisitor visitSuperclass() { return signatureVisitor(at); }
                    public SignatureVisitor visitInterface() { return signatureVisitor(at); }
                    public SignatureVisitor visitParameterType() { return signatureVisitor(at); }
                    public SignatureVisitor visitReturnType() { return signatureVisitor(at); }
                    public SignatureVisitor visitExceptionType() { return signatureVisitor(at); }
                };
            }
            AnnotationVisitor annotation(String d, String at) {
                descriptor(d, at);
                return new AnnotationVisitor(Opcodes.ASM9) {
                    public void visit(String n, Object v) { constant(v, at); }
                    public void visitEnum(String n, String d, String v) { descriptor(d, at); }
                    public AnnotationVisitor visitAnnotation(String n, String d) { return annotation(d, at); }
                    public AnnotationVisitor visitArray(String n) { return this; }
                };
            }
            void constant(Object v, String at) {
                if (v instanceof Type t) type(t, at);
                if (v instanceof Handle h) { member(h.getOwner(), h.getName(), h.getDesc(), at); }
                if (v instanceof ConstantDynamic c) {
                    descriptor(c.getDescriptor(), at); constant(c.getBootstrapMethod(), at);
                    for (int i = 0; i < c.getBootstrapMethodArgumentCount(); i++) constant(c.getBootstrapMethodArgument(i), at);
                }
            }
        }
        References r = new References();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            public void visit(int version, int access, String n, String s, String parent, String[] interfaces) {
                r.name(parent, "superclass");
                for (String i : interfaces) r.name(i, "interface");
                r.signature(s, "class signature", false);
            }
            public AnnotationVisitor visitAnnotation(String d, boolean visible) { return r.annotation(d, "class annotation"); }
            public AnnotationVisitor visitTypeAnnotation(int ref, TypePath path, String d, boolean visible) { return r.annotation(d, "class type annotation"); }
            public void visitOuterClass(String owner, String name, String descriptor) { r.name(owner, "enclosing method"); r.descriptor(descriptor, "enclosing method"); }
            public void visitNestHost(String host) { r.name(host, "nest host"); }
            public void visitPermittedSubclass(String child) { r.name(child, "permitted subclass"); }
            public RecordComponentVisitor visitRecordComponent(String name, String d, String s) {
                r.descriptor(d, "record " + name); r.signature(s, "record signature " + name, true);
                return new RecordComponentVisitor(Opcodes.ASM9) {
                    public AnnotationVisitor visitAnnotation(String d, boolean visible) { return r.annotation(d, "record annotation " + name); }
                    public AnnotationVisitor visitTypeAnnotation(int ref, TypePath p, String d, boolean visible) { return r.annotation(d, "record type annotation " + name); }
                };
            }
            public FieldVisitor visitField(int access, String n, String d, String s, Object value) {
                String at = "field " + n;
                r.descriptor(d, at); r.signature(s, at + " signature", true); r.constant(value, at);
                return new FieldVisitor(Opcodes.ASM9) {
                    public AnnotationVisitor visitAnnotation(String d, boolean visible) { return r.annotation(d, at + " annotation"); }
                    public AnnotationVisitor visitTypeAnnotation(int ref, TypePath p, String d, boolean visible) { return r.annotation(d, at + " type annotation"); }
                };
            }
            public MethodVisitor visitMethod(int access, String n, String d, String s, String[] exceptions) {
                String at = "method " + n + d;
                r.descriptor(d, at); r.signature(s, at + " signature", false);
                if (exceptions != null) for (String e : exceptions) r.name(e, at + " throws");
                return new MethodVisitor(Opcodes.ASM9) {
                    public AnnotationVisitor visitAnnotationDefault() { return r.annotation(null, at + " annotation default"); }
                    public AnnotationVisitor visitAnnotation(String d, boolean visible) { return r.annotation(d, at + " annotation"); }
                    public AnnotationVisitor visitParameterAnnotation(int p, String d, boolean visible) { return r.annotation(d, at + " parameter annotation"); }
                    public AnnotationVisitor visitTypeAnnotation(int ref, TypePath p, String d, boolean visible) { return r.annotation(d, at + " type annotation"); }
                    public AnnotationVisitor visitInsnAnnotation(int ref, TypePath p, String d, boolean visible) { return r.annotation(d, at + " instruction annotation"); }
                    public AnnotationVisitor visitTryCatchAnnotation(int ref, TypePath p, String d, boolean visible) { return r.annotation(d, at + " catch annotation"); }
                    public AnnotationVisitor visitLocalVariableAnnotation(int ref, TypePath p, Label[] start, Label[] end, int[] index, String d, boolean visible) { return r.annotation(d, at + " local annotation"); }
                    public void visitTypeInsn(int opcode, String t) { r.name(t, at + " type instruction"); }
                    public void visitFieldInsn(int opcode, String owner, String name, String d) { r.name(owner, at + " field owner"); r.descriptor(d, at + " field descriptor"); }
                    public void visitMethodInsn(int opcode, String owner, String name, String d, boolean itf) { r.member(owner, name, d, at + " call " + name); }
                    public void visitInvokeDynamicInsn(String name, String d, Handle bootstrap, Object... args) {
                        r.descriptor(d, at + " invokedynamic"); r.constant(bootstrap, at + " bootstrap");
                        for (Object a : args) r.constant(a, at + " bootstrap argument");
                    }
                    public void visitLdcInsn(Object value) { r.constant(value, at + " literal"); }
                    public void visitMultiANewArrayInsn(String d, int dimensions) { r.descriptor(d, at + " array"); }
                    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) { r.name(type, at + " catch"); }
                    public void visitLocalVariable(String name, String d, String s, Label start, Label end, int index) { r.descriptor(d, at + " local"); r.signature(s, at + " local signature", true); }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return new Scan(source, List.copyOf(new LinkedHashSet<>(edges)));
    }
    private CompiledDependencies() {}
}
