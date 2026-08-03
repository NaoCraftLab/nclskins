package com.naocraftlab.skins.core.api;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;


public final class MinecraftServiceUriPolicy {
    public static final URI PROFILE_SERVICE = URI.create("https://api.minecraftservices.com");
    public static final String TEXTURE_HOST = "textures.minecraft.net";

    private MinecraftServiceUriPolicy() {}

    public static boolean isAllowedTextureUri(URI uri) {
        Objects.requireNonNull(uri, "uri");
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean portAllowed = port == -1 || ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        return ("http".equals(scheme) || "https".equals(scheme))
                && TEXTURE_HOST.equalsIgnoreCase(uri.getHost())
                && uri.getUserInfo() == null
                && uri.getFragment() == null
                && portAllowed
                && uri.getPath() != null
                && uri.getPath().startsWith("/texture/");
    }
}
