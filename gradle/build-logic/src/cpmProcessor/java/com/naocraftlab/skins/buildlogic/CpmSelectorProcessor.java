package com.naocraftlab.skins.buildlogic;

import com.naocraftlab.skins.compat.cpm.MergedCpmSelector;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import org.spongepowered.asm.mixin.injection.selectors.TargetSelector;

public final class CpmSelectorProcessor extends AbstractProcessor {
    @Override public synchronized void init(ProcessingEnvironment environment) {
        super.init(environment);
        org.spongepowered.asm.util.logging.MessageRouter.setMessager(environment.getMessager());
        TargetSelector.register(MergedCpmSelector.class, "nclskins");
    }
    @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
    @Override public Set<String> getSupportedAnnotationTypes() { return Set.of("*"); }
    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) { return false; }
}
