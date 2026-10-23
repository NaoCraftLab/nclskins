package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.compat.cpm.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;

public final class CpmProjectOptionalProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            com.naocraftlab.skins.runtime.CpmProjectAdmissionGuardProbe.main(args);
            return;
        }
        var original=Thread.currentThread().getContextClassLoader();var plugin=new CpmMixinPlugin();
        boolean supported=CpmProjectBindings.supported(original);if(!supported)throw new AssertionError("healthy original shape");
        for(String shape:List.of("absent","missing-compiler","missing-log","double-log","missing-template-load","wrong-opcode","missing-defaults","missing-texture-method","missing-texture-client","double-texture-client","wrong-texture-interface")) {
            ClassLoader challenged=new ClassLoader(original){
                @Override public java.net.URL getResource(String name){if(shape.equals("absent")&&name.startsWith("com/tom/"))return null;return super.getResource(name);}
                @Override public InputStream getResourceAsStream(String name){
                    if(shape.equals("absent")&&name.startsWith("com/tom/"))return null;
                    try(var in=super.getResourceAsStream(name)) {
                        if(in==null)return null;byte[] bytes=in.readAllBytes();
                        if(!List.of("com/tom/cpm/shared/editor/Exporter.class", "com/tom/cpm/shared/editor/template/EditorTemplate.class",
                                "com/tom/cpm/shared/editor/Generators.class", "com/tom/cpm/shared/editor/util/GetFreeSkinSlots.class").contains(name))return new ByteArrayInputStream(bytes);
                        var n=new ClassNode();new ClassReader(bytes).accept(n,0);
                        if(shape.equals("missing-compiler"))n.methods.removeIf(m->m.name.equals("prepareDefinition"));
                        if(shape.equals("missing-defaults"))n.methods.removeIf(m->m.name.equals("getDefault"));
                        if(shape.equals("missing-texture-method"))n.methods.removeIf(m->m.name.startsWith("lambda$loadTextures$")&&m.desc.contains("BiConsumer"));
                        for(var m:n.methods)for(var i:m.instructions.toArray())if(i instanceof MethodInsnNode c){
                            if(m.name.startsWith("lambda$loadTextures$")&&m.desc.contains("BiConsumer")&&c.owner.equals("com/tom/cpm/shared/MinecraftClientAccess")&&c.name.equals("get")){
                                if(shape.equals("missing-texture-client"))m.instructions.remove(c);
                                if(shape.equals("double-texture-client"))m.instructions.insert(c,new MethodInsnNode(c.getOpcode(),c.owner,c.name,c.desc,c.itf));
                                if(shape.equals("wrong-texture-interface"))c.itf=false;
                            }
                            if(m.name.equals("prepareDefinition")&&c.owner.equals("com/tom/cpm/shared/util/Log")&&c.name.equals("info")){
                                if(shape.equals("missing-log"))m.instructions.remove(c);
                                if(shape.equals("double-log"))m.instructions.insert(c,new MethodInsnNode(c.getOpcode(),c.owner,c.name,c.desc,c.itf));
                                if(shape.equals("wrong-opcode"))c.setOpcode(Opcodes.INVOKEVIRTUAL);
                            }
                            if(shape.equals("missing-template-load")&&m.name.equals("load")&&c.owner.equals("com/tom/cpm/shared/definition/ModelDefinitionLoader")&&c.name.equals("load"))m.instructions.remove(c);
                        }
                        var out=new ClassWriter(0);n.accept(out);return new ByteArrayInputStream(out.toByteArray());
                    }catch(IOException e){throw new java.io.UncheckedIOException(e);}
                }
            };
            Thread.currentThread().setContextClassLoader(challenged);
            if(CpmProjectBindings.supported(challenged)||plugin.shouldApplyMixin("com.tom.cpm.shared.editor.Exporter","com.naocraftlab.skins.compat.cpm.mixin.CpmProjectExporterMixin"))throw new AssertionError("unsafe shape admitted: "+shape);
            if(!shape.equals("absent")&&!plugin.shouldApplyMixin("unused","com.naocraftlab.skins.compat.cpm.mixin.AnimationEngineMixin"))throw new AssertionError("unrelated CPM integration disabled");
            System.out.println("OPTIONAL "+shape+" PASS");
        }
        Thread.currentThread().setContextClassLoader(original);if(!CpmProjectBindings.supported(original))throw new AssertionError("original bytes preserved");
    }
}
