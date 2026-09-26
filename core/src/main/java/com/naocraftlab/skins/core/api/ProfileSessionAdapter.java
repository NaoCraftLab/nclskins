package com.naocraftlab.skins.core.api;

import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.GameSessionTokenUnavailableException;
import com.naocraftlab.skins.core.model.RemoteProfile;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.service.ProfileSessionPort;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public final class ProfileSessionAdapter implements ProfileSessionPort {
    private final ProfileApi api;
    public ProfileSessionAdapter(ProfileApi api) { this.api = Objects.requireNonNull(api); }
    public Optional<Duration> rateLimitRemaining() { return api.rateLimitRemaining(); }
    public <T> T withSession(GameSessionTokenSource source, Function<Effects, T> operation) {
        return source.withAccessToken(token -> {
            if (token == null || token.isBlank() || "0".equals(token)) {
                throw new GameSessionTokenUnavailableException();
            }
            return operation.apply(new Effects() {
                public RemoteProfile getProfile() throws ProfileApiException { return api.getProfile(token); }
                public void resetSkin() throws ProfileApiException { api.resetSkin(token); }
                public void uploadSkin(SkinVariant variant, byte[] bytes) throws ProfileApiException {
                    api.uploadSkin(token, variant, bytes);
                }
                public void activateCape(String id) throws ProfileApiException { api.activateCape(token, id); }
                public void deactivateCape() throws ProfileApiException { api.deactivateCape(token); }
            });
        });
    }
}
