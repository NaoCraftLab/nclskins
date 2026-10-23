package com.naocraftlab.skins.buildlogic.processor;

import com.naocraftlab.skins.buildlogic.terminal.CapeTerminalBindings;
import com.naocraftlab.skins.buildlogic.terminal.ExtractionTerminalBindings;
import com.naocraftlab.skins.buildlogic.terminal.BakedPreviewBindings;
import com.naocraftlab.skins.buildlogic.terminal.SubmissionPreviewBindings;
import java.util.*;
import net.neoforged.neoforgespi.transformation.*;

public final class CapeTerminalProvider implements ClassProcessorProvider {
    @Override public void createProcessors(Context context, Collector collector) { collector.add(new TerminalProcessor()); }
    public static final class TerminalProcessor implements ClassProcessor {
        public static final ProcessorName NAME = new ProcessorName("nclskins", "verification_terminals");
        @Override public ProcessorName name() { return NAME; }
        @Override public Set<ProcessorName> runsBefore() { return Set.of(ClassProcessorIds.MIXIN); }
        @Override public Set<ProcessorName> runsAfter() { return Set.of(ClassProcessorIds.COMPUTING_FRAMES); }
        @Override public boolean handlesClass(SelectionContext context) {
            return !context.empty() && (CapeTerminalBindings.owners(System.getProperty("nclskins.wavey.mode", "buffered")).contains(context.type().getInternalName())
                    || Boolean.getBoolean("nclskins.wavey.extraction") && ExtractionTerminalBindings.OWNERS.contains(context.type().getInternalName())
                    || BakedPreviewBindings.OWNERS.contains(context.type().getInternalName())
                    || !Boolean.getBoolean("nclskins.wavey.extraction") && SubmissionPreviewBindings.OWNERS.contains(context.type().getInternalName()));
        }
        @Override public ComputeFlags processClass(TransformationContext context) {
            int count = CapeTerminalBindings.apply(context.node());
            if (count == 0 && Boolean.getBoolean("nclskins.wavey.extraction")) count = ExtractionTerminalBindings.apply(context.node(), null);
            if (count == 0) count = BakedPreviewBindings.apply(context.node());
            if (count == 0 && !Boolean.getBoolean("nclskins.wavey.extraction")) count = SubmissionPreviewBindings.apply(context.node());
            if (count == 0) throw new AssertionError("UNLISTED_TERMINAL");
            context.audit("test-only CPU terminal substitution", Integer.toString(count));
            return ComputeFlags.COMPUTE_FRAMES;
        }
        @Override public void link(LinkContext context) {
            List<ProcessorName> names = new ArrayList<>(context.processors().keySet());
            int frames = names.indexOf(ClassProcessorIds.COMPUTING_FRAMES), own = names.indexOf(NAME), mixin = names.indexOf(ClassProcessorIds.MIXIN);
            if (frames < 0 || own <= frames || mixin <= own) throw new AssertionError("TERMINAL_ORDER " + names);
            int frameContext = names.indexOf(new ProcessorName("neoforge", "mixin_frame_context"));
            if (frameContext >= frames) throw new AssertionError("TERMINAL_ORDER frame-context " + names);
            System.out.println("CANONICAL_PRE_MIXIN_ORDER_PASS " + names);
        }
        @Override public void afterProcessing(AfterProcessingContext context) {
            System.out.println("CANONICAL_TERMINAL_APPLIED " + context.type().getInternalName());
        }
    }
}
