package com.naocraftlab.skins.diagnostics;


public final class DiagnosticSinks {
    private static final DiagnosticSink DISCARDING = (event, details) -> {};

    private DiagnosticSinks() {}

    public static DiagnosticSink discarding() {
        return DISCARDING;
    }
}
