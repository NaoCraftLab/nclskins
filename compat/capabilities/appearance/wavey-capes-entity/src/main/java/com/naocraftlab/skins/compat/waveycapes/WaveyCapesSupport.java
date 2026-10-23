package com.naocraftlab.skins.compat.waveycapes;

import com.naocraftlab.skins.client.ExactLocalPlayerScope;
import com.naocraftlab.skins.client.TextureUseValidity;
import com.naocraftlab.skins.compat.client.cape.CapePassMaterial;
import com.naocraftlab.skins.compat.client.cape.CapeTranslucentFoilMaterial;
import com.naocraftlab.skins.runtime.CapeProjection;
import dev.tr7zw.transition.mc.entitywrapper.PlayerWrapper;
import dev.tr7zw.waveycapes.render.CapeInfos;
import dev.tr7zw.waveycapes.render.CapePass;
import dev.tr7zw.waveycapes.render.CapeRenderer;
import dev.tr7zw.waveycapes.support.ModSupport;
import dev.tr7zw.waveycapes.support.SupportManager;
import java.util.List;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;

public final class WaveyCapesSupport implements ModSupport, CapeRenderer, AutoCloseable {
    private boolean active = true;
    private boolean closed;

    public static WaveyCapesSupport register() {
        WaveyCapesSupport support = new WaveyCapesSupport();
        try {
            CapePass pass = new CapePass(null, java.util.function.UnaryOperator.identity());
            ((CapeRenderer) support).getCapePasses(null);
            pass.renderType();
            pass.wrapper();
            new CapeInfos(support, null, false).isGlint();
            if (!SupportManager.getSupportedMods().add(support)) {
                support.close();
                return null;
            }
            return support;
        } catch (RuntimeException | LinkageError incompatible) {
            support.close();
            throw incompatible;
        }
    }

    private TextureUseValidity material(PlayerWrapper player) {
        if (!active || !CapeRenderThread.isClientThread() || player == null || player.getEntity() == null || player.isPlayerInvisible()
                || !player.isCapeVisible() || player.hasElytraEquipped()) return null;
        ResourceLocation installed = player.getCapeTexture();
        if (installed == null) return null;
        var actor = player.getEntity();
        TextureUseValidity preview = ExactLocalPlayerScope.capeValidity(actor, installed.toString());
        TextureUseValidity validity = preview != null ? preview : CapeProjection.captureCapeTexture(
                actor.getUUID(), actor.getScoreboardName(),
                actor.getUUID().equals(Minecraft.getInstance().getUser().getProfileId()), installed.toString());
        return validity.isValid() ? validity : null;
    }

    @Override public boolean shouldBeUsed(PlayerWrapper player) {
        try {
            return material(player) != null;
        } catch (LinkageError incompatible) {
            active = false;
            return false;
        }
    }

    @Override public List<CapePass> getCapePasses(PlayerWrapper player) {
        try {
            TextureUseValidity validity = material(player);
            if (validity == null) return List.of();
            ResourceLocation texture = player.getCapeTexture();
            var identity = java.util.function.UnaryOperator.<com.mojang.blaze3d.vertex.VertexConsumer>identity();
            RenderType base = new CapePassMaterial(CapeTranslucentFoilMaterial.forTexture(texture), validity);
            if (!CapeTranslucentFoilMaterial.inWorld()) return List.of(new CapePass(base, identity));
            boolean preview = ExactLocalPlayerScope.capeGlint(player.getEntity(), texture.toString()) != null;
            RenderType foil = new CapePassMaterial(preview ? RenderType.entityGlintDirect() : RenderType.entityGlint(), validity);
            return List.of(new CapePass(base, identity), new CapePass(foil, identity));
        } catch (LinkageError incompatible) {
            active = false;
            return List.of();
        }
    }

    @Override public CapeInfos getCapeInfo(PlayerWrapper player) {
        if (closed || !CapeRenderThread.isClientThread()) return null;
        try {
            ResourceLocation texture = player == null ? null : player.getCapeTexture();
            return texture == null ? null : new CapeInfos(this, RenderType.entityTranslucent(texture), false);
        } catch (LinkageError incompatible) {
            active = false;
            return null;
        }
    }

    @Override public CapeRenderer getRenderer() { return this; }
    @Override public int priority() { return 1; }
    @Override public boolean blockFeatureRenderer(Object player) { return false; }
    @Override public boolean vanillaUvValues() { return true; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        active = false;
        var supports = SupportManager.getSupportedMods().iterator();
        while (supports.hasNext()) {
            if (supports.next() == this) { supports.remove(); break; }
        }
    }
}
