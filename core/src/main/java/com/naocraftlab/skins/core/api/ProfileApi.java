package com.naocraftlab.skins.core.api;

import com.naocraftlab.skins.core.model.RemoteProfile;
import com.naocraftlab.skins.core.model.SkinVariant;
import java.time.Duration;
import java.util.Optional;


public interface ProfileApi {
    RemoteProfile getProfile(String accessToken) throws ProfileApiException;

    void uploadSkin(String accessToken, SkinVariant variant, byte[] pngBytes) throws ProfileApiException;

    void resetSkin(String accessToken) throws ProfileApiException;

    void activateCape(String accessToken, String capeId) throws ProfileApiException;

    void deactivateCape(String accessToken) throws ProfileApiException;


    default Optional<Duration> rateLimitRemaining() {
        return Optional.empty();
    }
}
