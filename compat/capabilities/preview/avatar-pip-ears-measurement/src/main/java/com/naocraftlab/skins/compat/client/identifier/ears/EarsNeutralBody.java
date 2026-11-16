package com.naocraftlab.skins.compat.client.identifier.ears;

import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import com.naocraftlab.skins.client.PreviewModelFrames;
import com.naocraftlab.skins.compat.client.identifier.measurement.AvatarEarsFitMode;
import com.naocraftlab.skins.runtime.CapeProjection;
import com.naocraftlab.skins.runtime.EffectiveSkinSource;
import com.unascribed.ears.api.features.EarsFeatures;
import diy.y2k.five.thermite.FeaturesHolder;
import diy.y2k.five.thermite.mixin.AccessorTextureManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

public final class EarsNeutralBody implements AvatarEarsFitMode.EarsBody {
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private final EffectiveSkinSource source;
    private final NativePlayerSkinLifecycle.Registration registration;
    private final AbstractTexture texture;
    private final EarsFeatures live;
    private final EarsFeatures neutral;

    @SuppressWarnings("deprecation")
    private EarsNeutralBody(EffectiveSkinSource source, NativePlayerSkinLifecycle.Registration registration,
            AbstractTexture texture, EarsFeatures features) {
        this.source = source;
        this.registration = registration;
        this.texture = texture;
        live = features;
        neutral = EarsFeatures.copyOf(features).animateTail(false)
                .wingAnimationMode(EarsFeatures.WingAnimationMode.NONE).capeEnabled(false).build();
    }

    public static EarsNeutralBody prepare(EffectiveSkinSource source) {
        if (source == null || !source.active() || !source.preview()) return null;
        var ticket = NativePlayerSkinLifecycle.registration(source.location()).orElse(null);
        if (ticket == null || ticket.state() != NativePlayerSkinLifecycle.State.READY) return null;
        var texture = textures().get(Identifier.parse(source.location()));
        if (!(texture instanceof FeaturesHolder holder) || !CapeProjection.ownsSkinTexture(source, texture)) return null;
        var features = holder.earsFeatures();
        return features == null || !features.enabled ? null : new EarsNeutralBody(source, ticket, texture, features);
    }

    private static java.util.Map<Identifier, AbstractTexture> textures() {
        return ((AccessorTextureManager) Minecraft.getInstance().getTextureManager()).ears$getTextures();
    }

    @Override public boolean current() {
        return source.active() && CapeProjection.ownsSkinTexture(source, texture)
                && NativePlayerSkinLifecycle.registration(source.location()).orElse(null) == registration
                && registration.state() == NativePlayerSkinLifecycle.State.READY;
    }

    @Override public PreviewModelFrames.Lease open(Object state, boolean reference) {
        if (!(state instanceof AvatarRenderState avatar) || !matches(avatar) || !current()
                || textures().get(Identifier.parse(source.location())) != texture)
            throw new IllegalStateException("Retired Ears measurement source");
        return new Scope(avatar, reference);
    }

    public static EarsFeatures select(EarsFeatures original, AvatarRenderState state) {
        var scope = ACTIVE.get();
        if (scope == null || scope.state != state) return original;
        if (!scope.owner.current() || !scope.owner.matches(state)
                || textures().get(Identifier.parse(scope.owner.source.location())) != scope.owner.texture
                || original != scope.owner.live)
            throw new IllegalStateException("Changed Ears measurement source");
        return scope.reference ? EarsFeatures.DISABLED : scope.owner.neutral;
    }

    private boolean matches(AvatarRenderState state) {
        return state.skin != null && state.skin.body().texturePath().toString().equals(source.location())
                && (state.skin.model() == net.minecraft.world.entity.player.PlayerModelType.SLIM)
                        == (source.model() == com.naocraftlab.skins.client.SkinModel.SLIM);
    }

    private final class Scope implements PreviewModelFrames.Lease {
        private final EarsNeutralBody owner = EarsNeutralBody.this;
        private final Thread thread = Thread.currentThread();
        private final Scope previous = ACTIVE.get();
        private AvatarRenderState state;
        private final boolean reference;
        private boolean closed;
        private Scope(AvatarRenderState state, boolean reference) {
            this.state = state;
            this.reference = reference;
            ACTIVE.set(this);
        }
        @Override public void close() {
            if (closed) return;
            if (Thread.currentThread() != thread || ACTIVE.get() != this)
                throw new IllegalStateException("Ears measurement scope closed out of order");
            closed = true;
            state = null;
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
        }
    }
}
