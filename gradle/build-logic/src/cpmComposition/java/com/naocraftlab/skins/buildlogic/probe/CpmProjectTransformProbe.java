package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import java.util.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import com.naocraftlab.skins.compat.cpm.CpmProjectBindings;

public final class CpmProjectTransformProbe {
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]);String mode=args.length>1?args[1]:"present";
        try(var in=ClassLoader.getSystemResourceAsStream("nclskins.cpm.mixins.json")){
            if(in==null)throw new AssertionError("production mixin registration absent");var config=com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in)).getAsJsonObject();
            for(String required:List.of("CpmProjectExporterInvoker","CpmProjectExporterMixin","CpmProjectTemplateMixin","CpmProjectTextureMixin"))if(!config.getAsJsonArray("client").contains(new com.google.gson.JsonPrimitive(required)))throw new AssertionError("production binding not registered: "+required);
        }
        if(mode.equals("present")&&!CpmProjectBindings.supported(Thread.currentThread().getContextClassLoader()))throw new AssertionError("original exact three binding anchors unavailable");
        MixinBootstrap.init();var phase=MixinEnvironment.class.getDeclaredMethod("gotoPhase",MixinEnvironment.Phase.class);phase.setAccessible(true);phase.invoke(null,MixinEnvironment.Phase.DEFAULT);
        var env=MixinEnvironment.getDefaultEnvironment();env.setSide(mode.equals("server")?MixinEnvironment.Side.SERVER:MixinEnvironment.Side.CLIENT);
        var ctor=Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();ctor.setAccessible(true);ctor.newInstance();MixinExtrasBootstrap.init();Mixins.addConfiguration("project-binding.json");
        var transformer=(IMixinTransformer)env.getActiveTransformer();
        if(!mode.equals("present")) {
            String name=CpmProjectTransformProbe.class.getName();byte[] bytes;
            try(var in=ClassLoader.getSystemResourceAsStream(name.replace('.','/')+".class")){bytes=in.readAllBytes();}
            transformer.transformClass(env,name,bytes);System.out.println("SAFE_STARTUP "+mode+" PASS");return;
        }
        for(String name:List.of("com.tom.cpm.shared.editor.Exporter","com.tom.cpm.shared.editor.template.EditorTemplate","com.tom.cpm.shared.editor.Generators","com.naocraftlab.skins.compat.cpm.mixin.CpmProjectExporterInvoker")) {
            String entry=name.replace('.','/')+".class";var origin=ClassLoader.getSystemResource(entry);
            byte[] before;try(var in=origin.openStream()){before=in.readAllBytes();}
            byte[] after=transformer.transformClass(env,name,before);Path dest=output.resolve(entry);Files.createDirectories(dest.getParent());Files.write(dest,after);
            var node=new ClassNode();new ClassReader(after).accept(node,0);
            if(name.endsWith("Exporter") && node.methods.stream().noneMatch(m->m.name.contains("nclskins$projectLog")))throw new AssertionError("log hook not applied");
            if(name.endsWith("EditorTemplate") && node.methods.stream().noneMatch(m->m.name.contains("nclskins$projectTemplate")))throw new AssertionError("template hook not applied");
            if(name.endsWith("Generators") && node.methods.stream().noneMatch(m->m.name.contains("nclskins$projectTexture")))throw new AssertionError("project texture hook not applied");
            System.out.println("TRANSFORM "+name+" ORIGIN "+origin+" "+before.length+" -> "+after.length);
        }
    }
}
