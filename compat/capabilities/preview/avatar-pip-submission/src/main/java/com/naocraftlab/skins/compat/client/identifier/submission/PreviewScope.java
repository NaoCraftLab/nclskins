package com.naocraftlab.skins.compat.client.identifier.submission;

import com.naocraftlab.skins.client.ExactLocalPlayerScope;
import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.PreviewRenderer;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;

public final class PreviewScope implements AutoCloseable {
    private static final ThreadLocal<PreviewScope> ACTIVE = new ThreadLocal<>();

    private final ExactLocalPlayerScope playerScope;
    private final PreviewRenderer.PreviewAppearance appearance;
    private final PlayerSkin skin;
    private final ItemStack chestEquipment;
    private boolean closed;

    private PreviewScope(
            AbstractClientPlayer player,
            PreviewRenderer.PreviewAppearance appearance,
            PlayerSkin skin,
            ItemStack chestEquipment) {
        playerScope = new ExactLocalPlayerScope(player);
        this.appearance = appearance;
        this.skin = skin;
        this.chestEquipment = chestEquipment;
    }

    public static PreviewScope open(
            Minecraft minecraft,
            AbstractClientPlayer player,
            PreviewRenderer.PreviewAppearance appearance,
            PlayerSkin skin,
            ItemStack chestEquipment) {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(appearance, "appearance");
        Objects.requireNonNull(skin, "skin");
        Objects.requireNonNull(chestEquipment, "chestEquipment");
        if (!minecraft.isSameThread() || minecraft.player != player) {
            throw new IllegalStateException(
                    "Editor preview scope requires the exact local player on the client thread");
        }
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("Editor preview scope is already active");
        }
        PreviewScope scope = new PreviewScope(
                player, appearance, skin, chestEquipment);
        ACTIVE.set(scope);
        return scope;
    }

    public static PlayerSkin skin(AbstractClientPlayer player, PlayerSkin original) {
        PreviewScope scope = current(player);
        return scope == null ? original : scope.skin;
    }

    public static Boolean modelPart(Avatar avatar, PlayerModelPart part) {
        PreviewScope scope = current(avatar);
        if (scope == null) {
            return null;
        }
        if (part == PlayerModelPart.CAPE) {
            return scope.skin.cape() != null;
        }
        return scope.appearance.outerLayerVisibility().visible(outerLayerPart(part));
    }

    public static ItemStack equipment(
            LivingEntity entity, EquipmentSlot slot, ItemStack original) {
        PreviewScope scope = current(entity);
        if (scope == null) {
            return original;
        }
        return slot == EquipmentSlot.CHEST ? scope.chestEquipment : ItemStack.EMPTY;
    }

    private static PreviewScope current(Object candidate) {
        PreviewScope scope = ACTIVE.get();
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
}
