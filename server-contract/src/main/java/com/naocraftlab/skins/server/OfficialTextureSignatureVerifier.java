package com.naocraftlab.skins.server;

import java.util.Optional;


@FunctionalInterface
public interface OfficialTextureSignatureVerifier {
    Optional<TextureAppearance> verify(
            SignedTexturesProperty property,
            ServerPlayerIdentity expectedIdentity);
}
