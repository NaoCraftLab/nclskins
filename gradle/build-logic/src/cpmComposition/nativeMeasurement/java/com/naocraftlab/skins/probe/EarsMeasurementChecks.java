package com.naocraftlab.skins.probe;

import com.naocraftlab.skins.client.NativePlayerSkinLifecycle;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.compat.client.identifier.ears.EarsNeutralBody;
import com.naocraftlab.skins.runtime.CapeProjection;
import com.unascribed.ears.api.features.EarsFeatures;
import diy.y2k.five.thermite.EarsTexture;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class EarsMeasurementChecks {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static void rejected(Runnable action) {
        try { action.run(); throw new AssertionError("foreign measurement scope admitted"); }
        catch (IllegalStateException expected) { check(expected.getSuppressed().length == 0, "clean source refusal"); }
    }
    private static AvatarRenderState state(Identifier id, PlayerModelType model) {
        var state = new AvatarRenderState();
        state.skin = new PlayerSkin(new ClientAsset.DownloadedTexture(id, "synthetic:owned"), null, null, model, false);
        return state;
    }
    public static void execute(Path image, String label) throws Exception {
        int before = checks;
        UUID profile = UUID.randomUUID();
        try (var registry = EarsProbe.start(Files.readAllBytes(image))) {
            Identifier id = EarsProbe.source;
            var ticket = NativePlayerSkinLifecycle.registration(id.toString()).orElseThrow();
            var pending = CapeProjection.effectiveSkin(profile, new UUID(0, 1), id.toString(), SkinModel.CLASSIC, true);
            check(ticket.state() == NativePlayerSkinLifecycle.State.PENDING && EarsNeutralBody.prepare(pending) == null,
                    "installed Ears and a managed ticket are not processed readiness");
            EarsProbe.queued().run();
            var texture = (EarsTexture) EarsProbe.Host.INSTANCE.textures.map.get(id);
            var raw = javax.imageio.ImageIO.read(image.toFile());
            int[] rawArgb = raw.getRGB(0, 0, 64, 64, null, 0, 64);

            var source = CapeProjection.effectiveSkin(profile, new UUID(0, 1), id.toString(), SkinModel.CLASSIC, true);
            check(source != pending && !pending.active(), "actual processing revises exact source in same opening");
            check(CapeProjection.ownsSkinTexture(source, texture) && java.util.Arrays.equals(source.pixels(), rawArgb),
                    "production admission binds exact processed object to original unmodified pixels");
            var body = EarsNeutralBody.prepare(source);
            check(body != null && body.current(), "same pending registration becomes genuinely ready without reopen");
            var live = texture.earsFeatures();
            var owned = state(id, PlayerModelType.WIDE);
            var foreign = state(id, PlayerModelType.WIDE);
            check(EarsNeutralBody.select(live, owned) == live, "live outside scope unchanged");
            try (var scope = body.open(owned, false)) {
                var neutral = EarsNeutralBody.select(live, owned);
                check(neutral != live && !neutral.animateTail && !neutral.capeEnabled
                        && neutral.wingAnimationMode == EarsFeatures.WingAnimationMode.NONE,
                        "immutable neutral feature copy excludes animation and managed cape");
                check(neutral.earMode == live.earMode && neutral.tailMode == live.tailMode
                        && neutral.tailSegments == live.tailSegments && neutral.wingMode == live.wingMode
                        && neutral.alfalfa == live.alfalfa, "BODY ears tail wings and own auxiliary data retained");
                check(EarsNeutralBody.select(live, foreign) == live, "foreign state is not intercepted");
                check(CompletableFuture.supplyAsync(() -> EarsNeutralBody.select(live, owned)).get() == live,
                        "foreign thread is not intercepted");
                try (var nested = body.open(owned, true)) {
                    check(EarsNeutralBody.select(live, owned) == EarsFeatures.DISABLED, "independent ordinary reference excludes all Ears BODY");
                }
                check(EarsNeutralBody.select(live, owned) == neutral, "nested reference restores exact neutral copy");
            }
            check(EarsNeutralBody.select(live, owned) == live && texture.earsFeatures() == live
                    && live.animateTail && live.capeEnabled && live.wingAnimationMode == EarsFeatures.WingAnimationMode.NORMAL,
                    "original live immutable features and natural animation restored");
            try (var thrown = body.open(owned, false)) { throw new IllegalArgumentException("synthetic-measurement-body"); }
            catch (IllegalArgumentException expected) {
                check(expected.getSuppressed().length == 0 && EarsNeutralBody.select(live,owned) == live,"thrown measurement body restores original feature scope");
            }
            rejected(() -> body.open(state(id, PlayerModelType.SLIM), false));
            rejected(() -> body.open(state(Identifier.parse("synthetic:wrong-source"), PlayerModelType.WIDE), false));
            var original = EarsProbe.Host.INSTANCE.textures.map.remove(id);
            check(EarsNeutralBody.prepare(source) == null, "READY ticket without actual native object is not readiness");
            rejected(() -> body.open(owned, false));
            EarsProbe.Host.INSTANCE.textures.map.put(id, original);
            var borrowed = EarsProbe.Host.INSTANCE.textures.map.values().stream().filter(value -> value != texture).findFirst().orElseThrow();
            EarsProbe.Host.INSTANCE.textures.map.put(id, borrowed);
            try {
                check(EarsNeutralBody.prepare(source) == null,"same identifier with wrong native texture object cannot satisfy readiness");
                rejected(() -> body.open(owned,false));
            } finally { EarsProbe.Host.INSTANCE.textures.map.put(id,original); }
            var unknown = CapeProjection.effectiveSkin(UUID.randomUUID(), new UUID(0,1), "synthetic:unknown", SkinModel.CLASSIC, true);
            check(EarsNeutralBody.prepare(unknown) == null, "unmanaged unknown source is not processed readiness");
            CapeProjection.retireSkin(unknown.cacheId());
            ticket.failed();
            check(!body.current() && EarsNeutralBody.prepare(source) == null,"failed ticket rejects already processed texture");
            ticket.retire();
            check(!body.current() && EarsNeutralBody.prepare(source) == null,"retired ticket rejects already processed texture");
            CapeProjection.retireSkin(profile);
            check(!body.current() && EarsNeutralBody.prepare(source) == null, "retired source cannot reuse processed features");
            rejected(() -> body.open(owned, false));
        }
        System.out.println("EARS_MEASUREMENT_CHECKS " + label + " checks=" + (checks-before));
    }
}
