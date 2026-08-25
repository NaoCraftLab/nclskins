package com.naocraftlab.skins.server.plugin.bukkit;

import com.naocraftlab.skins.server.ServerPlayerIdentity;
import com.naocraftlab.skins.server.SignedTexturesProperty;
import com.naocraftlab.skins.server.TextureAppearance;
import com.naocraftlab.skins.server.runtime.OfficialTextureAppearanceParser;
import org.bukkit.Bukkit;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;

public final class ExactAuthlibSignatureVerifier implements AuthlibSignatureVerifier {
    private static final String TEXTURES = "textures";

    private final Class<?> craftServerType;
    private final Constructor<?> propertyConstructor;
    private final Method minecraftServer;
    private final Method services;
    private final Method sessionService;
    private final Method securePropertyValue;

    private ExactAuthlibSignatureVerifier(
            Class<?> craftServerType,
            Constructor<?> propertyConstructor,
            Method minecraftServer,
            Method services,
            Method sessionService,
            Method securePropertyValue) {
        this.craftServerType = craftServerType;
        this.propertyConstructor = propertyConstructor;
        this.minecraftServer = minecraftServer;
        this.services = services;
        this.sessionService = sessionService;
        this.securePropertyValue = securePropertyValue;
    }

    public static ExactAuthlibSignatureVerifier resolve(
            ClassLoader classLoader,
            String craftServerPackage,
            String authlibFamily,
            boolean legacyMapped) throws ReflectiveOperationException {
        Objects.requireNonNull(authlibFamily, "authlibFamily");
        if (!authlibFamily.matches("authlib-v(4|6|7|9)")) {
            throw new IllegalArgumentException("Unsupported exact authlib family " + authlibFamily);
        }
        Class<?> craftServer = Class.forName(
                craftServerPackage + ".CraftServer", false, classLoader);
        Class<?> property = Class.forName(
                "com.mojang.authlib.properties.Property", false, classLoader);
        Class<?> sessionServiceType = Class.forName(
                "com.mojang.authlib.minecraft.MinecraftSessionService", false, classLoader);
        Constructor<?> propertyConstructor = property.getConstructor(
                String.class, String.class, String.class);
        Method hasSignature = property.getMethod("hasSignature");
        if (hasSignature.getReturnType() != boolean.class) {
            throw new NoSuchMethodException("Property#hasSignature()Z");
        }
        Method minecraftServer = craftServer.getMethod("getServer");
        Class<?> minecraftServerType = minecraftServer.getReturnType();
        Method services = null;
        Class<?> sessionOwner = minecraftServerType;
        String sessionMethod = legacyMapped ? "am" : "getSessionService";
        if (authlibFamily.equals("authlib-v7")
                || authlibFamily.equals("authlib-v9")) {
            services = minecraftServerType.getMethod("services");
            sessionOwner = services.getReturnType();
            sessionMethod = "sessionService";
        }
        Method sessionService = sessionOwner.getMethod(sessionMethod);
        if (sessionService.getReturnType() != sessionServiceType) {
            throw new NoSuchMethodException(minecraftServerType.getName()
                    + " exact session-service descriptor for " + authlibFamily);
        }
        Method securePropertyValue = sessionServiceType.getMethod(
                "getSecurePropertyValue", property);
        if (securePropertyValue.getReturnType() != String.class) {
            throw new NoSuchMethodException(
                    "MinecraftSessionService#getSecurePropertyValue(Property)String");
        }
        return new ExactAuthlibSignatureVerifier(
                craftServer, propertyConstructor, minecraftServer,
                services, sessionService, securePropertyValue);
    }

    @Override
    public Optional<TextureAppearance> verify(
            SignedTexturesProperty textures, ServerPlayerIdentity identity) {
        Objects.requireNonNull(textures, "textures");
        Objects.requireNonNull(identity, "identity");
        try {
            Object server = Bukkit.getServer();
            if (!craftServerType.isInstance(server)) return Optional.empty();
            Object nativeServer = minecraftServer.invoke(server);
            Object sessionOwner = services == null
                    ? nativeServer : services.invoke(nativeServer);
            Object verifier = sessionService.invoke(sessionOwner);
            Object property = propertyConstructor.newInstance(
                    TEXTURES, textures.value(), textures.signature());
            String verified = (String) securePropertyValue.invoke(verifier, property);
            if (!textures.value().equals(verified)) return Optional.empty();
            return OfficialTextureAppearanceParser.parseVerified(verified, identity);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (failure instanceof InvocationTargetException invocation
                    && invocation.getCause() instanceof Error fatal) {
                throw fatal;
            }
            return Optional.empty();
        }
    }
}
