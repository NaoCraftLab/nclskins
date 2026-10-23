package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.authlib.GameProfile;
import com.naocraftlab.skins.client.*;
import com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.*;
import com.naocraftlab.skins.diagnostics.DiagnosticSinks;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.syncher.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import static com.naocraftlab.skins.buildlogic.probe.wavey.ImmediateCapeHost.*;

public final class ImmediatePreviewInputs {
    public static PreviewRenderer<GuiGraphics> preview(Minecraft client) { return new RemotePlayerPreviewRenderer(DiagnosticSinks.discarding()); }
    public static BackEquipmentPreviewRenderer<GuiGraphics> thumbnails(Minecraft client) { return new SimplePreviewRenderer(); }
    public static String owner() { return "com.naocraftlab.skins.compat.client.resourcelocation.playerinfo.RemotePlayerPreviewRenderer"; }
    public static AbstractClientPlayer actor(ClientLevel level, GameProfile profile) throws Exception {
        AbstractClientPlayer actor = (AbstractClientPlayer) allocate(Class.forName(owner() + "$PreviewPlayer"));
        ImmediatePublicPreviewProbe.initialize(actor, level, profile);
        SynchedEntityData data = new SynchedEntityData(actor);
        field(actor, Entity.class, "entityData", data);
        for (var entry : defaults().entrySet()) define(data, entry.getKey(), entry.getValue());
        var method = Player.class.getDeclaredMethod("defineSynchedData"); method.setAccessible(true); method.invoke(actor);
        return actor;
    }
    @SuppressWarnings("unchecked")
    private static void define(SynchedEntityData data, String name, Object value) {
        data.define((EntityDataAccessor<Object>) field(null, Entity.class, name), value);
    }
    static Map<String, Object> defaults() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("DATA_SHARED_FLAGS_ID", (byte) 0); result.put("DATA_AIR_SUPPLY_ID", 300);
        result.put("DATA_CUSTOM_NAME", Optional.empty()); result.put("DATA_CUSTOM_NAME_VISIBLE", false);
        result.put("DATA_SILENT", false); result.put("DATA_NO_GRAVITY", false);
        result.put("DATA_POSE", Pose.STANDING); result.put("DATA_TICKS_FROZEN", 0);
        return result;
    }
}
