package com.naocraftlab.skins.diagnostics;

import java.util.function.Supplier;


@FunctionalInterface
public interface DiagnosticSink extends AutoCloseable {
    void report(DiagnosticEvent event, Supplier<DiagnosticDetails> details);

    @Override
    default void close() {}
}
