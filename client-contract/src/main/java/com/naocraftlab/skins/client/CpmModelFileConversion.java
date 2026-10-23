package com.naocraftlab.skins.client;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;

public interface CpmModelFileConversion {
    enum Availability { MISSING, UNAVAILABLE, READY }
    enum Reason { CPM_MISSING, CPM_UNAVAILABLE, INVALID_MODEL, IO_FAILED, MODEL_UNSUPPORTED, VARIANT_UNSUPPORTED, NESTED_MODEL,
        NONPORTABLE_OVERFLOW, PORTABILITY_UNSUPPORTED, CONVERSION_FAILED, INVALID_OUTPUT_PNG, STALE_RESOURCES, STALE_WORLD, CLOSED }

    Availability availability();
    AdmittedModel admit(InputStream source) throws Failure;
    AdmittedModel admitFile(Path source) throws Failure;
    Preparation prepare(AdmittedModel model, Raster base, long revision) throws Failure;

    interface DependencyAdmission extends CpmModelFileConversion {
        Admission admit(InputStream source, java.util.Map<String, byte[]> dependencies) throws Failure;
    }

    sealed interface Admission permits Ready, Dependency { }
    record Ready(AdmittedModel model) implements Admission {
        public Ready { Objects.requireNonNull(model); }
    }
    record Dependency(String link, java.net.URI uri) implements Admission {
        public Dependency { Objects.requireNonNull(link); Objects.requireNonNull(uri); }
        @Override public String toString() { return "Dependency"; }
    }

    sealed interface Preparation permits Inline, Publication { }

    record Inline(Raster raster) implements Preparation {
        public Inline { Objects.requireNonNull(raster); }
    }

    non-sealed interface Publication extends Preparation, AutoCloseable {
        SkinModel variant();
        long revision();
        byte[] wire();
        Preparation link(String confirmedPasteId) throws Failure;
        @Override void close();
    }

    interface AdmittedModel extends AutoCloseable {
        java.util.Optional<SkinModel> requiredVariant();
        java.util.Optional<Raster> automaticBase(SkinModel preferred) throws Failure;
        @Override void close();
    }

    record Raster(SkinModel variant, int[] argb) {
        public Raster {
            Objects.requireNonNull(variant);
            Objects.requireNonNull(argb);
            if (argb.length != 4096) throw new IllegalArgumentException("Expected canonical skin raster");
            argb = argb.clone();
        }
        @Override public int[] argb() { return argb.clone(); }
    }

    final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        private final Reason reason;
        public Failure(Reason reason) { super(Objects.requireNonNull(reason).name()); this.reason = reason; }
        public Reason reason() { return reason; }
    }
}
