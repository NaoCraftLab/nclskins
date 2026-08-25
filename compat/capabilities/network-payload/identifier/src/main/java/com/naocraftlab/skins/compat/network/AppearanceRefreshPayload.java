package com.naocraftlab.skins.compat.network;

import com.naocraftlab.skins.server.AppearanceRefreshSignalProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;


public final class AppearanceRefreshPayload implements CustomPacketPayload {
    public static final AppearanceRefreshPayload INSTANCE = new AppearanceRefreshPayload();
    public static final Type<AppearanceRefreshPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    AppearanceRefreshSignalProtocol.NAMESPACE,
                    AppearanceRefreshSignalProtocol.PATH));
    public static final StreamCodec<RegistryFriendlyByteBuf, AppearanceRefreshPayload> CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        if (payload != INSTANCE) {
                            throw new IllegalArgumentException("Unexpected appearance refresh payload");
                        }
                    },
                    buffer -> {
                        if (buffer.readableBytes() != 0) {
                            throw new IllegalArgumentException(
                                    "Appearance refresh payload must be empty");
                        }
                        return INSTANCE;
                    });

    private AppearanceRefreshPayload() {
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
