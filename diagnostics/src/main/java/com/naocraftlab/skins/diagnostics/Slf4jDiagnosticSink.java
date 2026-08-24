package com.naocraftlab.skins.diagnostics;

import org.slf4j.Logger;

import java.util.Objects;


public final class Slf4jDiagnosticSink extends BoundedDiagnosticSink {
    private final Logger logger;

    public Slf4jDiagnosticSink(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    protected boolean enabled(DiagnosticLevel level) {
        return switch (level) {
            case DEBUG -> logger.isDebugEnabled();
            case INFO -> logger.isInfoEnabled();
            case WARN -> logger.isWarnEnabled();
            case ERROR -> logger.isErrorEnabled();
        };
    }

    @Override
    protected void emit(DiagnosticLevel level, String message, Throwable failure) {
        switch (level) {
            case DEBUG -> logDebug(message, failure);
            case INFO -> logInfo(message, failure);
            case WARN -> logWarn(message, failure);
            case ERROR -> logError(message, failure);
        }
    }

    private void logDebug(String message, Throwable failure) {
        if (failure == null) {
            logger.debug(message);
        } else {
            logger.debug(message, failure);
        }
    }

    private void logInfo(String message, Throwable failure) {
        if (failure == null) {
            logger.info(message);
        } else {
            logger.info(message, failure);
        }
    }

    private void logWarn(String message, Throwable failure) {
        if (failure == null) {
            logger.warn(message);
        } else {
            logger.warn(message, failure);
        }
    }

    private void logError(String message, Throwable failure) {
        if (failure == null) {
            logger.error(message);
        } else {
            logger.error(message, failure);
        }
    }
}
