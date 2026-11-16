package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class ReferenceNeoTexture implements Opcodes {
    public static void main(String[] args)throws Exception{
        var node=PipNativeSetup.load("net/neoforged/neoforge/client/ClientHooks");int calls=0;
        for(var m:node.methods)if(m.name.equals("getArmorTexture")&&m.desc.equals("(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/client/resources/model/EquipmentClientInfo$Layer;Lnet/minecraft/resources/Identifier;)Lnet/minecraft/resources/Identifier;"))for(var ins:m.instructions.toArray())if(ins instanceof MethodInsnNode c&&c.owner.equals("net/neoforged/neoforge/client/extensions/common/IClientItemExtensions")&&c.name.equals("of")&&c.desc.equals("(Lnet/minecraft/world/item/ItemStack;)Lnet/neoforged/neoforge/client/extensions/common/IClientItemExtensions;")){
            if(c.getOpcode()!=INVOKESTATIC||!c.itf)throw new AssertionError("exact native texture metadata input");c.owner="com/naocraftlab/skins/buildlogic/probe/ReferenceNeoExtensions";c.name="extensions";c.itf=false;calls++;
        }
        if(calls!=1)throw new AssertionError("one transitive readonly item texture-extension metadata read "+calls);PipNativeSetup.save(Path.of(args[0]),node);System.out.println("REFERENCE_NEO_TEXTURE_METADATA one exact DEFAULT item-extension input; genuine ClientHooks getArmorTexture null-fallback and full caller algorithm retained");
    }
}
