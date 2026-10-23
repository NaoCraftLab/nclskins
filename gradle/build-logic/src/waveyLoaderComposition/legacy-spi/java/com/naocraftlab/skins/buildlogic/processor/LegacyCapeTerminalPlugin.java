package com.naocraftlab.skins.buildlogic.processor;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import com.naocraftlab.skins.buildlogic.terminal.ImmediateTerminalBindings;
import com.naocraftlab.skins.buildlogic.terminal.ImmediatePreviewBindings;
import java.util.EnumSet;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

public final class LegacyCapeTerminalPlugin implements ILaunchPluginService {
    @Override public String name() { return "nclskins_cape_terminals"; }
    @Override public EnumSet<Phase> handlesClass(Type type, boolean empty) {
        String mode = System.getProperty("nclskins.wavey.mode");
        return !empty && (ImmediateTerminalBindings.owners(mode).contains(type.getInternalName())
                || ImmediatePreviewBindings.owners(mode).contains(type.getInternalName()))
                ? EnumSet.of(Phase.BEFORE) : EnumSet.noneOf(Phase.class);
    }
    @Override public boolean processClass(Phase phase, ClassNode node, Type type) {
        if (phase != Phase.BEFORE || !node.name.equals(type.getInternalName())) throw new AssertionError("TERMINAL_PHASE_OWNER");
        String mode = System.getProperty("nclskins.wavey.mode");
        return ImmediateTerminalBindings.apply(node, mode) + ImmediatePreviewBindings.apply(node, mode) != 0;
    }
}
