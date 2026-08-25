package com.naocraftlab.skins.compat.client.identifier;

import com.mojang.authlib.properties.Property;
import com.naocraftlab.skins.client.SignedTextureVerifier;
import java.util.Optional;
import net.minecraft.client.Minecraft;


final class MinecraftSessionSignedTextureVerifier implements SignedTextureVerifier {
    @Override
    public Optional<String> verify(String value, String signature) {
        try {
            Property property = new Property("textures", value, signature);
            String verified = Minecraft.getInstance().services().sessionService()
                    .getSecurePropertyValue(property);
            return value.equals(verified) ? Optional.of(verified) : Optional.empty();
        } catch (RuntimeException invalidSignature) {
            return Optional.empty();
        }
    }
}
