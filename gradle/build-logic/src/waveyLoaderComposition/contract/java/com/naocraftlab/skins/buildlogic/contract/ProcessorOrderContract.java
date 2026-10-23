package com.naocraftlab.skins.buildlogic.contract;

import com.naocraftlab.skins.buildlogic.processor.CapeTerminalProvider.TerminalProcessor;
import java.util.*;
import net.neoforged.fml.classloading.transformation.ClassProcessorSet;
import net.neoforged.neoforgespi.transformation.*;

public final class ProcessorOrderContract {
    private static final ProcessorName FRAME_CONTEXT = new ProcessorName("neoforge", "mixin_frame_context");
    private static ClassProcessor participant(ProcessorName name, Set<ProcessorName> before, Set<ProcessorName> after) {
        return new ClassProcessor() {
            public ProcessorName name() { return name; }
            public Set<ProcessorName> runsBefore() { return before; }
            public Set<ProcessorName> runsAfter() { return after; }
            public boolean handlesClass(SelectionContext context) { return false; }
            public ComputeFlags processClass(TransformationContext context) { return ComputeFlags.NO_REWRITE; }
        };
    }
    public static void main(String[] args) {
        var terminal = new TerminalProcessor();
        var mixin = participant(ClassProcessorIds.MIXIN, Set.of(), Set.of(ClassProcessorIds.COMPUTING_FRAMES));
        var frame = participant(FRAME_CONTEXT, Set.of(ClassProcessorIds.COMPUTING_FRAMES), Set.of());
        boolean frameContext = Boolean.getBoolean("nclskins.wavey.frameContext");
        var participants = new ArrayList<ClassProcessor>(List.of(mixin, terminal));
        if (frameContext) participants.add(frame);
        final boolean[] linked = {false};
        participants.add(new ClassProcessor() {
            public ProcessorName name() { return new ProcessorName("nclskins", "order_assertion"); }
            public Set<ProcessorName> runsAfter() { return Set.of(ClassProcessorIds.MIXIN); }
            public boolean handlesClass(SelectionContext context) { return false; }
            public ComputeFlags processClass(TransformationContext context) { return ComputeFlags.NO_REWRITE; }
            public void link(LinkContext context) {
                var names = new ArrayList<>(context.processors().keySet());
                int frames = names.indexOf(ClassProcessorIds.COMPUTING_FRAMES), own = names.indexOf(TerminalProcessor.NAME), mix = names.indexOf(ClassProcessorIds.MIXIN);
                if (!(frames >= 0 && frames < own && own < mix) || frameContext && !(names.indexOf(FRAME_CONTEXT) >= 0 && names.indexOf(FRAME_CONTEXT) < frames))
                    throw new AssertionError("PINNED_PROCESSOR_ORDER " + names);
                linked[0] = true;
            }
        });
        ClassProcessorSet.of(participants.toArray(ClassProcessor[]::new)).link(name -> null);
        if (!linked[0]) throw new AssertionError("PINNED_LINK_NOT_REACHED");
        var reversed = new LinkedHashMap<ProcessorName, ClassProcessor>();
        reversed.put(ClassProcessorIds.MIXIN, mixin); reversed.put(TerminalProcessor.NAME, terminal);
        try { terminal.link(new ClassProcessor.LinkContext(reversed, null)); throw new IllegalStateException("REVERSED_ACCEPTED"); }
        catch (AssertionError expected) { if (!expected.getMessage().startsWith("TERMINAL_ORDER")) throw expected; }
        var cycle = participant(new ProcessorName("nclskins", "cycle_control"), Set.of(ClassProcessorIds.COMPUTING_FRAMES), Set.of(TerminalProcessor.NAME));
        try { ClassProcessorSet.of(mixin, terminal, cycle); throw new AssertionError("CYCLE_ACCEPTED"); }
        catch (RuntimeException expected) {
            if (!expected.getClass().getSimpleName().equals("CyclePresentException")) throw expected;
        }
        System.out.println("PINNED_PROCESSOR_ORDER_PASS frameContext=" + frameContext + "; actual ClassProcessorSet/link; reversed/cycle rejected; ZERO create/bootstrap");
    }
}
