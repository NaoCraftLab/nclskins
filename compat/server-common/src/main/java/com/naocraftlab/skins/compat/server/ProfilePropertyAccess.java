package com.naocraftlab.skins.compat.server;

import com.naocraftlab.skins.server.SignedTexturesProperty;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;


interface ProfilePropertyAccess {
    CurrentProfileTextures currentTextures(ServerPlayer player);

    void installTextures(
            ServerPlayer player, Optional<SignedTexturesProperty> officialTextures);
}
