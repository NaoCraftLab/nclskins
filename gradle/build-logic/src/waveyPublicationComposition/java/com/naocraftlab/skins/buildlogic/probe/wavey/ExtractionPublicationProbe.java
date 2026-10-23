package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.authlib.GameProfile;
import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.client.PreviewRenderer.*;
import com.naocraftlab.skins.compat.client.identifier.extraction.AvatarRenderStatePreviewRenderer;
import com.naocraftlab.skins.compat.waveycapes.WaveyCapesSupport;
import com.naocraftlab.skins.runtime.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.*;

public final class ExtractionPublicationProbe {
    private static AvatarRenderState published;
    private static AvatarRenderState selector;
    private static AvatarRenderer renderer;
    private static Object rawProxy;
    private static int publications;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    public static Object rawPreview(ClientLevel level, GameProfile profile) throws Exception {
        Class<?> type = Class.forName("com.naocraftlab.skins.compat.client.identifier.extraction.AvatarRenderStatePreviewRenderer$PreviewPlayer");
        Object actor = CapePublicationProbe.allocate(type);
        rawProxy = actor;
        set(net.minecraft.world.entity.Entity.class, actor, "uuid", profile.id());
        set(net.minecraft.world.entity.player.Player.class, actor, "gameProfile", profile);
        return actor;
    }
    public static void published(EntityRenderState state) {
        published = (AvatarRenderState) state;
        publications++;
    }
    private static void set(Class<?> owner, Object instance, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name); field.setAccessible(true); field.set(instance, value);
    }
    public static void main(String[] args) throws Exception {
        com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        runCase(args);
    }
    public static void reset() { published = null; selector = null; renderer = null; rawProxy = null; publications = 0; }
    public static void runCase(String[] args) throws Exception {
        try (HeadlessCapeClient client = HeadlessCapeClient.open(true);
                WaveyProducerFixture producer = new WaveyProducerFixture(Path.of(args[0]));
                CapeRegistry registry = new CapeRegistry();
                PreviewAssetCache<String> cache = new PreviewAssetCache<>(registry, TextureRegistry.TextureKind.IMAGE,
                        com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding());
                WaveyCapesSupport support = WaveyCapesSupport.register();
                AvatarRenderStatePreviewRenderer preview = new AvatarRenderStatePreviewRenderer(
                        com.naocraftlab.skins.diagnostics.DiagnosticSinks.discarding())) {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.player = CapePublicationProbe.allocate(LocalPlayer.class);
            set(net.minecraft.world.entity.Entity.class, minecraft.player, "uuid", producer.self);
            set(Minecraft.class, minecraft, "user", new User("Self", producer.self, "", Optional.empty(), Optional.empty()));
            renderer = CapePublicationProbe.allocate(AvatarRenderer.class);
            set(Minecraft.class, minecraft, "entityRenderDispatcher", CapePublicationProbe.allocate(PublicationDispatcher.class));
            set(Minecraft.class, minecraft, "deltaTracker", new DeltaTracker.Timer(20, 0, value -> value));
            dev.tr7zw.waveycapes.versionless.ModBase.config = new dev.tr7zw.waveycapes.versionless.config.Config();
            dev.tr7zw.waveycapes.versionless.ModBase.config.capeStyle = dev.tr7zw.waveycapes.versionless.CapeStyle.valueOf(args.length > 1 ? args[1] : "SMOOTH");
            cache.request("loser", () -> CompletableFuture.completedFuture(Optional.of(producer.firstFramePng)), () -> {});
            var handle = cache.handle("loser").orElseThrow();
            var token = cache.capture("loser");
            String world = CapeProjection.material(producer.self, "Self", true).capeLocation();
            var worldToken = CapeProjection.captureCapeTexture(producer.self, "Self", true, world);
            check(token != worldToken && token.isValid() && worldToken.isValid(), "Missing separate owner baselines");
            GuiGraphicsExtractor graphics = CapePublicationProbe.allocate(GuiGraphicsExtractor.class);
            AvatarRenderState positive = render(preview, graphics, CapePublicationProbe.appearance(handle, CapeMode.CAPE, true, token), PreviewIntent.EDITOR_DRAFT);
            check(CapePublicationProbe.material(positive) != null && CapePublicationProbe.material(positive).validity() == token
                    && support.shouldBeUsed(CapePublicationProbe.wrapper(positive)),
                    "EXTRACTION_PUBLICATION_MISSING: changed producer did not publish cache-owned losing inspection");
            check(positive == CapePublicationProbe.lastRawState() && rawProxy == CapePublicationProbe.lastRawActor(), "Extraction callback/state identity changed");
            check(positive != selector && positive.skin.model().toString().equalsIgnoreCase("slim"), "Selected output/model identity changed");
            CapePublicationProbe.collect(positive, support);
            var draft = render(preview, graphics, CapePublicationProbe.appearance(handle, CapeMode.CAPE, true, token), PreviewIntent.EDITOR_DRAFT);
            check(CapePublicationProbe.material(draft).validity() == token && draft != positive, "EDITOR_DRAFT_PUBLICATION");
            CapePublicationProbe.collect(draft, support);
            AvatarRenderState invalid = render(preview, graphics,
                    CapePublicationProbe.appearance(handle, CapeMode.CAPE, true, TextureUseValidity.INVALID));
            check(CapePublicationProbe.material(invalid).validity() == TextureUseValidity.INVALID
                    && !support.shouldBeUsed(CapePublicationProbe.wrapper(invalid)), "Uncapturable preview borrowed world incarnation");
            for (CapeMode mode : CapeMode.values()) {
                AvatarRenderState negative = render(preview, graphics, CapePublicationProbe.appearance(
                        new TextureRegistry.TextureHandle(world, 128, 64), mode, mode != CapeMode.CAPE, worldToken));
                check(CapePublicationProbe.material(negative) == null && !support.shouldBeUsed(CapePublicationProbe.wrapper(negative)),
                        "Extraction false/OFF/elytra borrowed world material");
                if (mode == CapeMode.ELYTRA) {
                    negative.chestEquipment.set(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                    check(negative.chestEquipment.hasFoil() && CapePublicationProbe.wrapper(negative).hasElytraEquipped(), "Native elytra foil changed");
                }
            }
            check(ExactLocalPlayerScope.capeGlint(minecraft.player, handle.location()) == null, "Actual extraction scope leaked");
            check(rawProxy != minecraft.player, "Preview proxy/local identity conflated");
            var adjacent = CapePublicationProbe.publish(renderer, CapePublicationProbe.actor(producer.self, "Self", world));
            check(CapePublicationProbe.material(adjacent).validity() == worldToken, "Next world actor inherited cache material");
            cache.close();
            check(!support.shouldBeUsed(CapePublicationProbe.wrapper(positive)) && worldToken.isValid(), "Cache retirement borrowed live world token");
            check(producer.profileReads.get() == 1 && producer.imageReads.get() == 1, "Extraction producer caused source I/O");
            System.out.println("EXTRACTION_PUBLICATION_PASS actual PreviewAssetCache/appearance -> public production render -> actual selector/AvatarPreviewScope/native createRenderState/production world hook -> changed configure/publication -> exact GUI state -> genuine support/collector; false/OFF/native enchanted elytra/scope/retirement; publications=" + publications);
        }
    }
    private static AvatarRenderState render(AvatarRenderStatePreviewRenderer preview, GuiGraphicsExtractor graphics, PreviewAppearance appearance) {
        return render(preview, graphics, appearance, PreviewIntent.EDITOR_DRAFT);
    }
    private static AvatarRenderState render(AvatarRenderStatePreviewRenderer preview, GuiGraphicsExtractor graphics, PreviewAppearance appearance, PreviewIntent intent) {
        published = null;
        preview.render(graphics, new PreviewRequest(appearance, 0, 0, 100, 160, 0, 0, 1, intent));
        check(published != null, "Production render did not reach GUI publication terminal");
        return published;
    }
    public static AvatarRenderer<?> selected(EntityRenderState state) {
        selector = (AvatarRenderState) state;
        return renderer;
    }
}
