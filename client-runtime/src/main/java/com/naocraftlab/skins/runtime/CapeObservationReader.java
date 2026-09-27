package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.core.png.PngValidator;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface CapeObservationReader {
    Optional<Duration> cooldownRemaining();
    long accountEpoch();
    void accountChanged();
    Result observe(UUID account, String canonicalName, long epoch);
    enum Kind { PRESENT, ABSENT, FAILURE }
    record Result(Kind kind, PngValidator.CapePng cape, boolean rateLimited) {}
}
