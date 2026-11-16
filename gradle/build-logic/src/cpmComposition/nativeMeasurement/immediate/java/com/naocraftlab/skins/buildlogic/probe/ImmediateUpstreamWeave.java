package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/** Normal selected CPM/Ears weave for the immediate native renderer bindings. */
public final class ImmediateUpstreamWeave {
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);
        MixinBootstrap.init();var phase=MixinEnvironment.class.getDeclaredMethod("gotoPhase",MixinEnvironment.Phase.class);phase.setAccessible(true);phase.invoke(null,MixinEnvironment.Phase.DEFAULT);
        var env=MixinEnvironment.getDefaultEnvironment();env.setSide(MixinEnvironment.Side.CLIENT);
        var constructor=Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();constructor.setAccessible(true);constructor.newInstance();com.llamalad7.mixinextras.MixinExtrasBootstrap.init();
        Mixins.addConfiguration("ears.mixins.json");Mixins.addConfiguration("cpm.mixins.json");
        String family=System.getProperty("nclskins.upstream.family");
        if("fabric".equals(family))UpstreamRendererWeave.decorateFabric(out);
        else if(!"neoforge".equals(family))throw new AssertionError("unknown upstream family "+family);
        var connection=(java.net.JarURLConnection)ClassLoader.getSystemResource("com/tom/cpm/client/PlayerRenderManager.class").openConnection();
        var jar=connection.getJarFile();com.google.gson.JsonObject config;
        try(var in=jar.getInputStream(jar.getJarEntry("cpm.mixins.shared.json"))) { config=com.google.gson.JsonParser.parseString(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject(); }
        List<String> requiredMixins=List.of("render.EquipmentLayerRendererMixin","render.AvatarRendererMixin","AvatarRenderStateMixin","render.LivingRendererMixin","render.CapeLayerMixin","render.WingsLayerMixin");
        var chosen=new com.google.gson.JsonArray();
        for(String required:requiredMixins) {
            if(!config.getAsJsonArray("client").asList().stream().anyMatch(x->x.getAsString().equals(required)))throw new AssertionError("pinned CPM mixin declaration "+required);
            chosen.add(required);
        }
        config.add("client",chosen);config.add("mixins",new com.google.gson.JsonArray());
        Path configFile=Path.of(args[1],"nclskins.immediate-equipment.mixins.json");Files.createDirectories(configFile.getParent());Files.writeString(configFile,config.toString());Mixins.addConfiguration(configFile.getFileName().toString());
        var transformer=(IMixinTransformer)env.getActiveTransformer();
        for(String name:List.of("net.minecraft.client.renderer.entity.player.AvatarRenderer","net.minecraft.client.renderer.entity.state.AvatarRenderState","net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer","net.minecraft.client.renderer.texture.TextureManager","net.minecraft.client.renderer.entity.LivingEntityRenderer","net.minecraft.client.renderer.entity.layers.CapeLayer","net.minecraft.client.renderer.entity.layers.WingsLayer")) {
            String entry=name.replace('.','/')+".class";var resource=ClassLoader.getSystemResource(entry);if(resource==null)throw new AssertionError("missing actual native owner "+name);
            byte[] before;try(var in=resource.openStream()){before=in.readAllBytes();}
            byte[] after=transformer.transformClass(env,name,before);if(Arrays.equals(before,after))throw new AssertionError("expected normal upstream weave "+name);
            var file=out.resolve(entry);Files.createDirectories(file.getParent());Files.write(file,after);var sha=MessageDigest.getInstance("SHA-256");
            System.out.println("UPSTREAM_BINDING "+name+" source="+resource+" before="+HexFormat.of().formatHex(sha.digest(before))+" after="+HexFormat.of().formatHex(sha.digest(after)));
        }
    }
}
