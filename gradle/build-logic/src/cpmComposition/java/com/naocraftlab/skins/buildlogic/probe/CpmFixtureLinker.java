package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

public final class CpmFixtureLinker {
    private static final Map<String, String> ABI_NAMES = Map.ofEntries(
            Map.entry("com/naocraftlab/skins/buildlogic/probe/fixture/raw/ResourceLocation", "net/minecraft/resources/ResourceLocation"),
            Map.entry("com/naocraftlab/skins/buildlogic/probe/fixture/raw/Minecraft", "net/minecraft/client/Minecraft"),
            Map.entry("com/naocraftlab/skins/buildlogic/probe/fixture/raw/HttpTexture", "net/minecraft/client/renderer/texture/HttpTexture"),
            Map.entry("com/naocraftlab/skins/buildlogic/probe/fixture/raw/TextureManager", "net/minecraft/client/renderer/texture/TextureManager"),
            Map.entry("com/naocraftlab/skins/buildlogic/probe/fixture/raw/AbstractTexture", "net/minecraft/client/renderer/texture/AbstractTexture"),
            Map.entry("com/naocraftlab/skins/buildlogic/probe/fixture/raw/DynamicTexture", "net/minecraft/client/renderer/texture/DynamicTexture"),
            Map.entry("com/naocraftlab/skins/buildlogic/probe/fixture/raw/PlayerSkin", "net/minecraft/client/resources/PlayerSkin"),
            Map.entry("com/naocraftlab/skins/buildlogic/probe/fixture/raw/DefaultPlayerSkin", "net/minecraft/client/resources/DefaultPlayerSkin"),
            Map.entry("com/naocraftlab/skins/buildlogic/probe/fixture/raw/NativeImage", "com/mojang/blaze3d/platform/NativeImage"),
            Map.entry("com/naocraftlab/skins/buildlogic/probe/fixture/material/RenderTypes", "net/minecraft/client/renderer/rendertype/RenderTypes"),
            Map.entry("com/naocraftlab/skins/buildlogic/probe/fixture/material/RenderType", "net/minecraft/client/renderer/rendertype/RenderType"),
            Map.entry("com/naocraftlab/skins/buildlogic/probe/fixture/material/EquipmentLayerRenderer", "net/minecraft/client/renderer/entity/layers/EquipmentLayerRenderer"),
            Map.entry("com/naocraftlab/skins/buildlogic/probe/fixture/material/EquipmentClientInfo", "net/minecraft/client/resources/model/EquipmentClientInfo"),
            Map.entry("com/naocraftlab/skins/buildlogic/probe/fixture/material/Model", "net/minecraft/client/model/Model"),
            Map.entry("com/naocraftlab/skins/buildlogic/probe/fixture/material/PlayerRenderManager", "com/tom/cpm/client/PlayerRenderManager"),
            Map.entry("com/naocraftlab/skins/buildlogic/probe/fixture/material/ModelTexture", "com/tom/cpm/client/ModelTexture"));

    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Remapper names = new Remapper(Opcodes.ASM9) {
            @Override public String map(String name) {
                int nested = name.indexOf('$');
                String owner = nested < 0 ? name : name.substring(0, nested);
                String mapped = ABI_NAMES.get(owner);
                return mapped == null ? name : mapped + (nested < 0 ? "" : name.substring(nested));
            }
        };
        int count = 0;
        try (var files = Files.walk(input)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                ClassReader reader = new ClassReader(Files.readAllBytes(file));
                String linkedName = names.map(reader.getClassName());
                if (linkedName.equals(reader.getClassName())) {
                    throw new IllegalStateException("Unregistered NCL fixture: " + reader.getClassName());
                }
                ClassWriter writer = new ClassWriter(0);
                reader.accept(new ClassRemapper(writer, names), 0);
                Path linked = output.resolve(linkedName + ".class");
                Files.createDirectories(linked.getParent());
                Files.write(linked, writer.toByteArray());
                count++;
            }
        }
        if (count == 0) throw new IllegalStateException("No NCL fixtures compiled");
        System.out.println("Linked " + count + " NCL synthetic fixture classes to transient test ABI names");
    }
}
