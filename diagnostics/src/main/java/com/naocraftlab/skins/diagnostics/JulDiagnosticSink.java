package com.naocraftlab.skins.diagnostics;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;


public final class JulDiagnosticSink extends BoundedDiagnosticSink {
    private final Logger logger;
    private final Level debugLevel;
    private final BooleanSupplier debugEnabled;

    public JulDiagnosticSink(
            Logger logger, Level debugLevel, BooleanSupplier debugEnabled) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.debugLevel = Objects.requireNonNull(debugLevel, "debugLevel");
        this.debugEnabled = Objects.requireNonNull(debugEnabled, "debugEnabled");
    }

    @Override
    protected boolean enabled(DiagnosticLevel level) {
        return switch (level) {
            case DEBUG -> debugEnabled.getAsBoolean();
            case INFO -> logger.isLoggable(Level.INFO);
            case WARN -> logger.isLoggable(Level.WARNING);
            case ERROR -> logger.isLoggable(Level.SEVERE);
        };
    }

    @Override
    protected void emit(DiagnosticLevel level, String message, Throwable failure) {
        Level julLevel = switch (level) {
            case DEBUG -> debugLevel;
            case INFO -> Level.INFO;
            case WARN -> Level.WARNING;
            case ERROR -> Level.SEVERE;
        };
        if (failure == null) {
            logger.log(julLevel, message);
        } else {
            logger.log(julLevel, message, failure);
        }
    }
}
