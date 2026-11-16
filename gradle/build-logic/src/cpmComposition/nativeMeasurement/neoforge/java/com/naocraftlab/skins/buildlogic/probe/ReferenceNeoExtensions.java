package com.naocraftlab.skins.buildlogic.probe;

import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

public final class ReferenceNeoExtensions {
    public static IClientItemExtensions extensions(ItemStack stack){
        if(stack!=ItemStack.EMPTY&&!ReferenceEquipment.isOwned(stack))throw new AssertionError("only exact owned synthetic/empty equipment metadata admitted");return IClientItemExtensions.DEFAULT;
    }
    public static void main(String[] args)throws Exception{
        var node=PipNativeSetup.load("net/minecraft/client/renderer/entity/layers/EquipmentLayerRenderer");int calls=0;
        for(var m:node.methods)for(var ins:m.instructions.toArray())if(ins instanceof MethodInsnNode c&&c.owner.equals("net/neoforged/neoforge/client/extensions/common/IClientItemExtensions")&&c.name.equals("of")&&c.desc.equals("(Lnet/minecraft/world/item/ItemStack;)Lnet/neoforged/neoforge/client/extensions/common/IClientItemExtensions;")){
            if(c.getOpcode()!=Opcodes.INVOKESTATIC||!c.itf)throw new AssertionError("exact native interface metadata invocation");c.owner=Type.getInternalName(ReferenceNeoExtensions.class);c.name="extensions";c.itf=false;calls++;
        }
        if(calls!=1)throw new AssertionError("one loader item-extension readonly input "+calls);PipNativeSetup.save(Path.of(args[0]),node);System.out.println("REFERENCE_NEO_EXTENSION_INPUT exact source-owned synthetic/empty item DEFAULT metadata; original native renderLayers, model choice, textures and CPM material hooks retained");
    }
}
