package com.naocraftlab.skins.compat.client;

import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.GameSessionTokenUnavailableException;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;


public final class MinecraftGameSessionTokenSource implements GameSessionTokenSource {
    @Override
    public SessionIdentity currentSession() {
        User user = currentUser();
        return new SessionIdentity(user.getProfileId(), user.getName());
    }

    @Override
    public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
        Objects.requireNonNull(request, "request");
        return request.execute(activeAccessToken(currentUser()));
    }

    @Override
    public <T, E extends Exception> T withSession(SessionRequest<T, E> request) throws E {
        Objects.requireNonNull(request, "request");
        User user = currentUser();
        SessionIdentity identity = new SessionIdentity(user.getProfileId(), user.getName());
        return request.execute(identity, activeAccessToken(user));
    }

    private static User currentUser() {
        Minecraft minecraft = Objects.requireNonNull(Minecraft.getInstance(), "Minecraft is unavailable");
        return Objects.requireNonNull(minecraft.getUser(), "Minecraft user is unavailable");
    }

    private static String activeAccessToken(User user) {
        String accessToken = Objects.requireNonNull(user, "user").getAccessToken();
        if (accessToken == null || accessToken.isBlank()) {
            throw new GameSessionTokenUnavailableException();
        }
        return accessToken;
    }

    @Override
    public String toString() {
        return "MinecraftGameSessionTokenSource[token=<redacted>]";
    }
}
