package com.naocraftlab.skins.compat.client.resourcelocation.skinlookup;

import com.naocraftlab.skins.client.OuterLayerPart;
import com.naocraftlab.skins.client.ExactLocalPlayerScope;
import com.naocraftlab.skins.client.PreviewRenderer;
import com.naocraftlab.skins.client.SkinModel;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class PreviewScope implements AutoCloseable {
    private static final ThreadLocal<PreviewScope> ACTIVE = new ThreadLocal<>();

    private final AbstractClientPlayer player;
    private final ExactLocalPlayerScope playerScope;
    private final PreviewRenderer.PreviewAppearance appearance;
    private final PlayerSkin skin;
    private final boolean elytra;
    private boolean closed;

    private PreviewScope(
            AbstractClientPlayer player,
            PreviewRenderer.PreviewAppearance appearance) {
        this.player = player;
        playerScope = new ExactLocalPlayerScope(player);
        this.appearance = appearance;
        ResourceLocation body = location(appearance.skin().location());
        ResourceLocation selectedCape = appearance.cape()
                .map(handle -> location(handle.location()))
                .orElse(null);
        ResourceLocation cape = appearance.capeMode() == PreviewRenderer.CapeMode.CAPE
                ? selectedCape
                : null;
        ResourceLocation elytraTexture = appearance.capeMode() == PreviewRenderer.CapeMode.ELYTRA
                ? selectedCape
                : null;
        PlayerSkin.Model model = appearance.model() == SkinModel.SLIM
                ? PlayerSkin.Model.SLIM
                : PlayerSkin.Model.WIDE;
        skin = new PlayerSkin(body, "", cape, elytraTexture, model, false);
        elytra = elytraTexture != null;
    }

    public static PreviewScope open(
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
        PreviewScope scope = new PreviewScope(player, appearance);
        ACTIVE.set(scope);
        return scope;
    }

    public static PlayerSkin skin(AbstractClientPlayer player, PlayerSkin original) {
        PreviewScope scope = current(player);
        return scope == null ? original : scope.skin;
    }

    public static Boolean modelPart(Player player, PlayerModelPart part) {
        PreviewScope scope = current(player);
        if (scope == null) {
            return null;
        }
        if (part == PlayerModelPart.CAPE) {
            return scope.skin.capeTexture() != null;
        }
        return scope.appearance.outerLayerVisibility().visible(outerLayerPart(part));
    }

    public static ItemStack equipment(LivingEntity entity, EquipmentSlot slot, ItemStack original) {
        PreviewScope scope = current(entity);
        if (scope == null) {
            return original;
        }
        return slot == EquipmentSlot.CHEST && scope.elytra
                ? new ItemStack(Items.ELYTRA)
                : ItemStack.EMPTY;
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
}
