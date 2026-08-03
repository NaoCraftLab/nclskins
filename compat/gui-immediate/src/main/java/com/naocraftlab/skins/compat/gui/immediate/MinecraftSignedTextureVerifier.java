package com.naocraftlab.skins.compat.gui.immediate;

import com.mojang.authlib.properties.Property;
import com.naocraftlab.skins.client.SignedTextureVerifier;
import java.util.Optional;
import net.minecraft.client.Minecraft;


public final class MinecraftSignedTextureVerifier implements SignedTextureVerifier {
    @Override
    public Optional<String> verify(String value, String signature) {
        try {
            Property property = new Property("textures", value, signature);
            String verified = Minecraft.getInstance().getMinecraftSessionService()
                    .getSecurePropertyValue(property);
            return value.equals(verified) ? Optional.of(verified) : Optional.empty();
        } catch (RuntimeException invalidSignature) {
            return Optional.empty();
        }
    }
}
