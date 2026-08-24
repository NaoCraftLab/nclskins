package com.naocraftlab.skins.diagnostics;

import java.time.Duration;
import java.util.Objects;

import static com.naocraftlab.skins.diagnostics.DiagnosticLevel.DEBUG;
import static com.naocraftlab.skins.diagnostics.DiagnosticLevel.ERROR;
import static com.naocraftlab.skins.diagnostics.DiagnosticLevel.INFO;
import static com.naocraftlab.skins.diagnostics.DiagnosticLevel.WARN;
import static com.naocraftlab.skins.diagnostics.SuppressionPolicy.ALWAYS;
import static com.naocraftlab.skins.diagnostics.SuppressionPolicy.ONCE;
import static com.naocraftlab.skins.diagnostics.SuppressionPolicy.TRAFFIC_THRESHOLD;
import static com.naocraftlab.skins.diagnostics.SuppressionPolicy.WINDOW;


public enum DiagnosticEvent {
    CLIENT_SESSION_WARMUP_FAILED(DEBUG, "Session warmup deferred after a local failure", WINDOW),
    CLIENT_RECONNECT_FAILED(DEBUG, "Reconnect recovery deferred after a local failure", WINDOW),
    CLIENT_CURRENT_APPEARANCE_FAILED(DEBUG, "Current appearance could not be inspected", WINDOW),
    CLIENT_CAPE_CACHE_FAILED(DEBUG, "Cape cache operation failed", WINDOW),
    CLIENT_PREFERENCES_LOAD_FAILED(DEBUG, "Client preferences could not be loaded", WINDOW),
    CLIENT_PREFERENCES_SAVE_FAILED(WARN, "Client preferences could not be saved", WINDOW),
    CLIENT_PICKER_FAILED(DEBUG, "Native file picker failed", WINDOW),
    CLIENT_IMPORT_FAILED(DEBUG, "Appearance import operation failed", WINDOW),
    CLIENT_BUNDLED_SKIN_MISSING(ERROR, "Required bundled skin is unavailable", ONCE),
    CLIENT_RECONCILIATION_FAILED(WARN, "Appearance reconciliation failed", WINDOW),
    CLIENT_RECONCILIATION_CLEANUP_FAILED(DEBUG, "Reconciliation cleanup failed", WINDOW),
    CLIENT_READINESS_FAILED(DEBUG, "Server readiness probe failed", WINDOW),
    CLIENT_ASYNC_OPERATION_FAILED(DEBUG, "Client background operation failed", WINDOW),
    CLIENT_PREVIEW_SOURCE_FAILED(DEBUG, "Preview source could not be prepared", WINDOW),
    CLIENT_PREVIEW_LIVE_DISABLED(WARN, "Live editor preview disabled after renderer failure", ALWAYS),
    CLIENT_PREVIEW_LAYER_SKIPPED(WARN, "Incompatible editor preview layer skipped", ALWAYS),
    CLIENT_TEXTURE_REGISTER_FAILED(DEBUG, "Preview texture registration failed", WINDOW),
    CLIENT_TEXTURE_LOAD_FAILED(DEBUG, "Preview texture load failed", WINDOW),
    CLIENT_TEXTURE_RELEASE_FAILED(DEBUG, "Preview texture release failed", WINDOW),
    CLIENT_APPEARANCE_REFRESH_FAILED(DEBUG, "Local appearance refresh was deferred", WINDOW),
    CLIENT_MUTATION_SETTLEMENT_FAILED(WARN, "Remote appearance settlement failed", WINDOW),
    SERVER_IDENTITY_ATTESTATION_FAILED(DEBUG, "Connection identity attestation failed closed", WINDOW),
    SERVER_PUBLICATION_FAILED(WARN, "Appearance publication failed", WINDOW),
    SERVER_PUBLICATION_CLEANUP_FAILED(DEBUG, "Publication cleanup failed", WINDOW),
    SERVER_SIGNATURE_VERIFICATION_FAILED(DEBUG, "Official texture verification failed", WINDOW),
    SERVER_DELIVERY_FAILED(DEBUG, "Observer update delivery failed", WINDOW),
    SERVER_RETRACK_FAILED(DEBUG, "Observer retracking failed", WINDOW),
    SERVER_RETRY_FAILED(DEBUG, "Appearance publication retry failed", WINDOW),
    SERVER_COORDINATOR_CLOSE_FAILED(DEBUG, "Server refresh shutdown cleanup failed", WINDOW),
    PLUGIN_STARTUP_FAILED(ERROR, "Server plugin disabled after startup failure", ALWAYS),
    PLUGIN_REFRESH_REJECTED(DEBUG, "Server refresh request rejected", WINDOW),
    PLUGIN_REFRESH_OVERLOADED(DEBUG, "Server refresh requests remain overloaded", TRAFFIC_THRESHOLD),
    PLUGIN_REFRESH_EXPIRED(DEBUG, "Server refresh requests remain expired", TRAFFIC_THRESHOLD),
    PLUGIN_REFRESH_FAILED(WARN, "Server refresh request failed", WINDOW),
    RELAY_MALFORMED(DEBUG, "Malformed proxy relay traffic rejected", TRAFFIC_THRESHOLD),
    RELAY_STALE(DEBUG, "Stale proxy relay traffic rejected", TRAFFIC_THRESHOLD),
    PLUGIN_READY(INFO, "NCL_SKINS_PLUGIN_READY", ALWAYS),
    PROXY_READY(INFO, "NCL_SKINS_PROXY_READY", ALWAYS),
    RECOVERY_COMPLETED(DEBUG, "Diagnostic episode recovered", ALWAYS),
    UNEXPECTED_CLEANUP_FAILED(DEBUG, "Cleanup operation failed", WINDOW);

    public static final Duration DEFAULT_WINDOW = Duration.ofSeconds(60);
    public static final long TRAFFIC_WARNING_THRESHOLD = 10L;

    private final DiagnosticLevel level;
    private final String message;
    private final SuppressionPolicy suppression;

    DiagnosticEvent(
            DiagnosticLevel level,
            String message,
            SuppressionPolicy suppression) {
        this.level = Objects.requireNonNull(level, "level");
        this.message = Objects.requireNonNull(message, "message");
        this.suppression = Objects.requireNonNull(suppression, "suppression");
        if (message.isBlank() || message.indexOf('\n') >= 0 || message.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Diagnostic message must be fixed and single-line");
        }
    }

    public DiagnosticLevel level() {
        return level;
    }

    public String message() {
        return message;
    }

    public SuppressionPolicy suppression() {
        return suppression;
    }
}
