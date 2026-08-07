package com.naocraftlab.skins.compat.v1_20_1.client;

import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.ExactLocalPlayerScope;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.SkinModel;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class Minecraft1201PreviewScope implements AutoCloseable {
    private static final ThreadLocal<Minecraft1201PreviewScope> ACTIVE = new ThreadLocal<>();

    private final AbstractClientPlayer player;
    private final ExactLocalPlayerScope playerScope;
    private final ResourceLocation skin;
    private final ResourceLocation cape;
    private final ResourceLocation elytra;
    private final String model;
    private final PreviewRenderer.PreviewAppearance appearance;
    private boolean closed;

    private Minecraft1201PreviewScope(
            AbstractClientPlayer player,
            PreviewRenderer.PreviewAppearance appearance) {
        this.player = player;
        playerScope = new ExactLocalPlayerScope(player);
        this.appearance = appearance;
        skin = location(appearance.skin().location());
        ResourceLocation selectedCape = appearance.cape()
                .map(handle -> location(handle.location()))
                .orElse(null);
        cape = appearance.capeMode() == PreviewRenderer.CapeMode.CAPE ? selectedCape : null;
        elytra = appearance.capeMode() == PreviewRenderer.CapeMode.ELYTRA ? selectedCape : null;
        model = appearance.model() == SkinModel.SLIM ? "slim" : "default";
    }

    public static Minecraft1201PreviewScope open(
            Minecraft minecraft,
            AbstractClientPlayer player,
            PreviewRenderer.PreviewAppearance appearance) {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(appearance, "appearance");
        if (!minecraft.isSameThread() || minecraft.player != player) {
            throw new IllegalStateException("Editor preview scope requires the local player on the client thread");
        }
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("Editor preview scope is already active");
        }
        Minecraft1201PreviewScope scope = new Minecraft1201PreviewScope(player, appearance);
        ACTIVE.set(scope);
        return scope;
    }

    public static ResourceLocation skin(AbstractClientPlayer player, ResourceLocation original) {
        Minecraft1201PreviewScope scope = current(player);
        return scope == null ? original : scope.skin;
    }

    public static ResourceLocation cape(AbstractClientPlayer player, ResourceLocation original) {
        Minecraft1201PreviewScope scope = current(player);
        return scope == null ? original : scope.cape;
    }

    public static ResourceLocation elytra(AbstractClientPlayer player, ResourceLocation original) {
        Minecraft1201PreviewScope scope = current(player);
        return scope == null ? original : scope.elytra;
    }

    public static String model(AbstractClientPlayer player, String original) {
        Minecraft1201PreviewScope scope = current(player);
        return scope == null ? original : scope.model;
    }

    public static Boolean textureLoaded(AbstractClientPlayer player, Texture texture) {
        Minecraft1201PreviewScope scope = current(player);
        if (scope == null) {
            return null;
        }
        return switch (texture) {
            case SKIN -> true;
            case CAPE -> scope.cape != null;
            case ELYTRA -> scope.elytra != null;
        };
    }

    public static Boolean modelPart(Player player, PlayerModelPart part) {
        Minecraft1201PreviewScope scope = current(player);
        if (scope == null) {
            return null;
        }
        if (part == PlayerModelPart.CAPE) {
            return scope.cape != null;
        }
        return scope.appearance.outerLayerVisibility().visible(outerLayerPart(part));
    }

    public static ItemStack equipment(LivingEntity entity, EquipmentSlot slot, ItemStack original) {
        Minecraft1201PreviewScope scope = current(entity);
        if (scope == null) {
            return original;
        }
        return slot == EquipmentSlot.CHEST && scope.elytra != null
                ? new ItemStack(Items.ELYTRA)
                : ItemStack.EMPTY;
    }

    private static Minecraft1201PreviewScope current(Object candidate) {
        Minecraft1201PreviewScope scope = ACTIVE.get();
        return scope != null && scope.playerScope.appliesTo(candidate) ? scope : null;
    }

    private static OuterLayerPart outerLayerPart(PlayerModelPart part) {
        return switch (part) {
            case HAT -> OuterLayerPart.HEAD;
            case JACKET -> OuterLayerPart.BODY;
            case LEFT_SLEEVE -> OuterLayerPart.LEFT_ARM;
            case RIGHT_SLEEVE -> OuterLayerPart.RIGHT_ARM;
            case LEFT_PANTS_LEG -> OuterLayerPart.LEFT_LEG;
            case RIGHT_PANTS_LEG -> OuterLayerPart.RIGHT_LEG;
            case CAPE -> throw new IllegalArgumentException("cape is not an outer-layer part");
        };
    }

    private static ResourceLocation location(String value) {
        ResourceLocation location = ResourceLocation.tryParse(value);
        if (location == null) {
            throw new IllegalArgumentException("Invalid preview texture location");
        }
        return location;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (ACTIVE.get() != this) {
                throw new IllegalStateException("Editor preview scope closed out of order");
            }
            ACTIVE.remove();
            playerScope.close();
        }
    }

    public enum Texture {
        SKIN,
        CAPE,
        ELYTRA
    }
}
