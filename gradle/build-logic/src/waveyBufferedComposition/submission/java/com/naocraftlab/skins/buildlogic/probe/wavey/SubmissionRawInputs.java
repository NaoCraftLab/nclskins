package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.entity.ClientAvatarState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.syncher.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

public final class SubmissionRawInputs {
    public static Object rawPreview(ClientLevel level, GameProfile profile) throws Exception {
        Object actor = CapePublicationProbe.allocate(Class.forName("com.naocraftlab.skins.compat.client.identifier.submission.SubmissionPreviewRenderer$PreviewPlayer"));
        set(Entity.class, actor, "uuid", profile.id());
        set(net.minecraft.world.entity.player.Player.class, actor, "gameProfile", profile);
        return actor;
    }
    public static void set(Class<?> owner, Object instance, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name); field.setAccessible(true); field.set(instance, value);
    }
    public static void setId(Object actor, int value) {}
    public static void setItemSlot(Object actor, EquipmentSlot slot, ItemStack value) {}
    public static Vec3 position(Object actor) { return Vec3.ZERO; }
    public static void setPos(Object actor, Vec3 value) {}
    public static void setPos(Object actor, double x, double y, double z) {}
    public static void setYRot(Object actor, float value) {}
    public static void setXRot(Object actor, float value) {}
    public static void setOldPosAndRot(Object actor) {}
    public static void setOnGround(Object actor, boolean value) {}
    public static void setPose(Object actor, Pose value) {}
    public static void setInvisible(Object actor, boolean value) {}
    public static double getX(Object actor) { return 0; }
    public static double getY(Object actor) { return 0; }
    public static double getZ(Object actor) { return 0; }
    public static void tick(Object actor) {}
    public static ClientAvatarState avatarState(Object actor) { return null; }
    public static void tick(Object state, Vec3 position, Vec3 velocity) {}
    public static SynchedEntityData getEntityData(Object actor) { return null; }
    public static void set(Object data, EntityDataAccessor<?> accessor, Object value) {}
}
