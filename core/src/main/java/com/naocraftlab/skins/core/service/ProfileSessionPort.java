package com.naocraftlab.skins.core.service;

import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.core.api.ProfileApiException;
import com.naocraftlab.skins.core.model.RemoteProfile;
import com.naocraftlab.skins.core.model.SkinVariant;
import java.time.Duration;
import java.util.Optional;

public interface ProfileSessionPort {
    <T> T withSession(GameSessionTokenSource source, java.util.function.Function<Effects, T> operation);
    Optional<Duration> rateLimitRemaining();

    interface Effects {
        RemoteProfile getProfile() throws ProfileApiException;
        void resetSkin() throws ProfileApiException;
        void uploadSkin(SkinVariant variant, byte[] bytes) throws ProfileApiException;
        void activateCape(String id) throws ProfileApiException;
        void deactivateCape() throws ProfileApiException;
    }
}
