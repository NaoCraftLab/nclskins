package com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.ears;

import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import com.naocraftlab.skins.client.PreviewModelFrames;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.RemotePlayerEarsFitMode;
import com.naocraftlab.skins.runtime.CapeProjection;
import com.naocraftlab.skins.runtime.EffectiveSkinSource;
import com.unascribed.ears.api.Cork;
import com.unascribed.ears.api.EarsStateType;
import com.unascribed.ears.api.OverrideResult;
import com.unascribed.ears.api.features.EarsFeatures;
import com.unascribed.ears.api.registry.EarsStateOverriderRegistry;
import diy.y2k.five.buzz.support.EarsFeaturesHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;

public final class EarsNeutralBody implements RemotePlayerEarsFitMode.EarsBody {
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private final EffectiveSkinSource source;
    private final NativePlayerSkinLifecycle.Registration registration;
    private final AbstractTexture texture;
    private final EarsFeatures live;
    private EarsFeatures neutral;
    private EarsNeutralBody(EffectiveSkinSource source, NativePlayerSkinLifecycle.Registration registration,
            AbstractTexture texture, EarsFeatures live) {
        this.source = source; this.registration = registration; this.texture = texture; this.live = live;
    }
    private static AbstractTexture texture(EffectiveSkinSource source) {
        return Minecraft.getInstance().getTextureManager().getTexture(new ResourceLocation(source.location()), null);
    }
    public static EarsNeutralBody prepare(EffectiveSkinSource source) {
        if (source == null || !source.active() || !source.preview()) return null;
        var ticket = NativePlayerSkinLifecycle.registration(source.location()).orElse(null);
        if (ticket == null || ticket.state() != NativePlayerSkinLifecycle.State.READY) return null;
        var texture = texture(source);
        if (!(texture instanceof EarsFeaturesHolder holder) || !CapeProjection.ownsSkinTexture(source, texture)) return null;
        var features = holder.getEarsFeatures();
        return features == null || !features.enabled ? null : new EarsNeutralBody(source, ticket, texture, features);
    }
    @Override public boolean current() {
        return source.active() && CapeProjection.ownsSkinTexture(source, texture) && texture(source) == texture
                && ((EarsFeaturesHolder) texture).getEarsFeatures() == live
                && NativePlayerSkinLifecycle.registration(source.location()).orElse(null) == registration
                && registration.state() == NativePlayerSkinLifecycle.State.READY;
    }
    @SuppressWarnings("deprecation")
    @Override public PreviewModelFrames.Lease open(Object player, boolean reference) {
        if (!(player instanceof AbstractClientPlayer avatar) || !matches(avatar) || !current())
            throw new IllegalStateException("Retired Ears measurement source");
        if (!reference && neutral == null) neutral = EarsFeatures.copyOf(live).animateTail(false)
                .wingAnimationMode(EarsFeatures.WingAnimationMode.NONE).capeEnabled(false).build();
        return new Scope(avatar, reference);
    }
    private boolean matches(AbstractClientPlayer player) {
        return player.getSkinTextureLocation().toString().equals(source.location())
                && player.getModelName().equals("slim") == (source.model() == SkinModel.SLIM);
    }
    public static EarsFeatures select(EarsFeatures original, Object player) {
        var scope = ACTIVE.get();
        if (scope == null || scope.player != player) return original;
        if (!scope.owner.current() || !scope.owner.matches(scope.player) || original != scope.owner.live)
            throw new IllegalStateException("Changed Ears measurement source");
        return scope.reference ? EarsFeatures.DISABLED : scope.owner.neutral;
    }
    private final class Scope implements PreviewModelFrames.Lease {
        private final EarsNeutralBody owner = EarsNeutralBody.this;
        private final Thread thread = Thread.currentThread();
        private final Scope previous = ACTIVE.get();
        private final boolean reference;
        private final Cork override;
        private AbstractClientPlayer player;
        private boolean closed;
        private Scope(AbstractClientPlayer player, boolean reference) {
            this.player = player; this.reference = reference;
            override = EarsStateOverriderRegistry.register("nclskins-neutral-body", (state, peer) -> {
                if (ACTIVE.get() == this && this.player == peer && current()
                        && (state == EarsStateType.WEARING_ELYTRA || state == EarsStateType.WEARING_CHESTPLATE
                        || state == EarsStateType.GLIDING || state == EarsStateType.SWIMMING
                        || state == EarsStateType.CREATIVE_FLYING)) return OverrideResult.FALSE;
                return OverrideResult.DEFAULT;
            });
            ACTIVE.set(this);
        }
        @Override public void close() {
            if (closed) return;
            if (Thread.currentThread() != thread || ACTIVE.get() != this)
                throw new IllegalStateException("Ears measurement scope closed out of order");
            closed = true; player = null;
            try { override.cork(); }
            finally { if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous); }
        }
    }
}
