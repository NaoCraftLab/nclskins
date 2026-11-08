package com.naocraftlab.skins.buildlogic.contract;

import com.naocraftlab.skins.buildlogic.processor.CapeTerminalProvider;
import com.naocraftlab.skins.buildlogic.processor.CapeTerminalProvider.TerminalProcessor;
import com.naocraftlab.skins.buildlogic.processor.CapeTerminalProvider.NativeDeviceTerminalProcessor;
import com.naocraftlab.skins.buildlogic.terminal.SubmissionPreviewBindings;
import java.util.*;
import net.neoforged.fml.classloading.transformation.ClassProcessorSet;
import net.neoforged.neoforgespi.transformation.*;
import org.objectweb.asm.Type;

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
    private static ClassProcessor misordered(ClassProcessor actual, Set<ProcessorName> before, Set<ProcessorName> after) {
        return new ClassProcessor() {
            public ProcessorName name() { return actual.name(); }
            public Set<ProcessorName> runsBefore() { return before; }
            public Set<ProcessorName> runsAfter() { return after; }
            public boolean handlesClass(SelectionContext context) { return actual.handlesClass(context); }
            public ComputeFlags processClass(TransformationContext context) { return actual.processClass(context); }
            public void link(LinkContext context) { actual.link(context); }
        };
    }
    private static void reject(String marker, String reason, ClassProcessor... processors) {
        try { ClassProcessorSet.of(processors).link(name -> null); throw new IllegalStateException(marker + "_ACCEPTED"); }
        catch (AssertionError expected) { if (!expected.getMessage().startsWith(reason)) throw expected; }
        System.out.println(marker);
    }
    public static void main(String[] args) {
        var terminal = new TerminalProcessor();
        var device = new NativeDeviceTerminalProcessor();
        var mixin = participant(ClassProcessorIds.MIXIN, Set.of(), Set.of(ClassProcessorIds.COMPUTING_FRAMES));
        var frame = participant(FRAME_CONTEXT, Set.of(ClassProcessorIds.COMPUTING_FRAMES), Set.of());
        boolean frameContext = Boolean.getBoolean("nclskins.wavey.frameContext");
        var participants = new ArrayList<ClassProcessor>();
        new CapeTerminalProvider().createProcessors(null, participants::add);
        if (!participants.stream().map(ClassProcessor::name).toList().equals(List.of(TerminalProcessor.NAME, NativeDeviceTerminalProcessor.NAME)))
            throw new AssertionError("PINNED_REGISTRATION_CLOSURE");
        participants.add(mixin);
        if (frameContext) participants.add(frame);
        final boolean[] linked = {false};
        participants.add(new ClassProcessor() {
            public ProcessorName name() { return new ProcessorName("nclskins", "order_assertion"); }
            public Set<ProcessorName> runsAfter() { return Set.of(NativeDeviceTerminalProcessor.NAME); }
            public boolean handlesClass(SelectionContext context) { return false; }
            public ComputeFlags processClass(TransformationContext context) { return ComputeFlags.NO_REWRITE; }
            public void link(LinkContext context) {
                var names = new ArrayList<>(context.processors().keySet());
                int frames = names.indexOf(ClassProcessorIds.COMPUTING_FRAMES), raw = names.indexOf(TerminalProcessor.NAME);
                int mix = names.indexOf(ClassProcessorIds.MIXIN), post = names.indexOf(NativeDeviceTerminalProcessor.NAME);
                if (!(frames >= 0 && frames < raw && raw < mix && mix < post)
                        || frameContext && !(names.indexOf(FRAME_CONTEXT) >= 0 && names.indexOf(FRAME_CONTEXT) < frames))
                    throw new AssertionError("PINNED_PROCESSOR_ORDER " + names);
                linked[0] = true;
            }
        });
        var processors = ClassProcessorSet.of(participants.toArray(ClassProcessor[]::new));
        processors.link(name -> null);
        if (!linked[0]) throw new AssertionError("PINNED_LINK_NOT_REACHED");
        for (String owner : SubmissionPreviewBindings.NATIVE_OWNERS) {
            var selected = new ClassProcessor.SelectionContext(Type.getObjectType(owner), false);
            boolean submission = !Boolean.getBoolean("nclskins.wavey.extraction");
            if (terminal.handlesClass(selected) || device.handlesClass(selected) != submission)
                throw new AssertionError("NATIVE_DEVICE_SELECTION_PHASE " + owner);
            if (device.handlesClass(new ClassProcessor.SelectionContext(Type.getObjectType(owner), true)))
                throw new AssertionError("NATIVE_DEVICE_EMPTY_CLASS");
        }
        for (String owner : SubmissionPreviewBindings.OWNERS)
            if (device.handlesClass(new ClassProcessor.SelectionContext(Type.getObjectType(owner), false)))
                throw new AssertionError("PRODUCER_SELECTED_POST_MIXIN");
        if (device.handlesClass(new ClassProcessor.SelectionContext(Type.getObjectType("net/minecraft/client/Minecraft"), false)))
            throw new AssertionError("UNKNOWN_DEVICE_OWNER");
        var reversed = new LinkedHashMap<ProcessorName, ClassProcessor>();
        reversed.put(ClassProcessorIds.MIXIN, mixin); reversed.put(TerminalProcessor.NAME, terminal);
        try { terminal.link(new ClassProcessor.LinkContext(reversed, null)); throw new IllegalStateException("REVERSED_ACCEPTED"); }
        catch (AssertionError expected) { if (!expected.getMessage().startsWith("TERMINAL_ORDER")) throw expected; }
        reject("DEVICE_PRE_MIXIN_REJECTED", "NATIVE_DEVICE_ORDER", mixin, terminal,
                misordered(device, Set.of(ClassProcessorIds.MIXIN), Set.of(ClassProcessorIds.COMPUTING_FRAMES)));
        reject("PRODUCER_POST_MIXIN_REJECTED", "TERMINAL_ORDER", mixin, device,
                misordered(terminal, Set.of(NativeDeviceTerminalProcessor.NAME), Set.of(ClassProcessorIds.MIXIN)));
        var cycle = participant(new ProcessorName("nclskins", "cycle_control"), Set.of(ClassProcessorIds.COMPUTING_FRAMES), Set.of(NativeDeviceTerminalProcessor.NAME));
        try { ClassProcessorSet.of(mixin, terminal, device, cycle); throw new AssertionError("CYCLE_ACCEPTED"); }
        catch (RuntimeException expected) {
            if (!expected.getClass().getSimpleName().equals("CyclePresentException")) throw expected;
        }
        System.out.println("PINNED_PROCESSOR_ORDER_PASS frameContext=" + frameContext + "; actual provider/ClassProcessorSet/link; raw < Mixin < device; reversed/device-pre/producer-post/cycle rejected; ZERO create/bootstrap");
    }
}
