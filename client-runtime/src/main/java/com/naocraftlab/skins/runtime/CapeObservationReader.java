package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.png.PreparedCapeAsset;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface CapeObservationReader {
    Optional<Duration> cooldownRemaining();
    long accountEpoch();
    void accountChanged();
    Result observe(UUID account, String canonicalName, long epoch);
    enum Kind { PRESENT, ABSENT, FAILURE }
    default void discard(PreparedCapeAsset asset) throws java.io.IOException {
    }
    record Result(Kind kind, PngValidator.CapePng cape, PreparedCapeAsset asset,
            boolean capeGlint, boolean rateLimited) {
        Result(Kind kind, PngValidator.CapePng cape, boolean rateLimited) {
            this(kind, cape, null, false, rateLimited);
        }
        Result(Kind kind, PngValidator.CapePng cape, PreparedCapeAsset asset,
                boolean rateLimited) {
            this(kind, cape, asset, false, rateLimited);
        }
    }
}
