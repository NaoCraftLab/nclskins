package com.naocraftlab.skins.buildlogic.probe.wavey;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.entity.ClientAvatarState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.syncher.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

public final class SubmissionRawInputs {
    private static int deviceReads;
    public static int deviceReads() { return deviceReads; }
    private static final class DepthTarget extends com.mojang.blaze3d.pipeline.RenderTarget {
        DepthTarget(Texture depth) {
            super("wavey-main-depth-input", true);
            depthTexture = depth;
        }
    }
    public static final class MainTargetInput implements AutoCloseable {
        private final net.minecraft.client.Minecraft minecraft;
        private final com.mojang.blaze3d.pipeline.RenderTarget previous;
        private final Texture depth = new Texture(8, "wavey-main-depth-input", com.mojang.blaze3d.textures.TextureFormat.DEPTH32, 1, 1, 1, 1);
        private final DepthTarget target = new DepthTarget(depth);
        private boolean closed;
        public MainTargetInput(net.minecraft.client.Minecraft minecraft) throws Exception {
            this.minecraft = minecraft;
            if (net.minecraft.client.Minecraft.getInstance() != minecraft) {
                depth.close();
                throw new AssertionError("LIVE_MAIN_TARGET_WRONG_CLIENT");
            }
            previous = minecraft.getMainRenderTarget();
            try { set(net.minecraft.client.Minecraft.class, minecraft, "mainRenderTarget", target); }
            catch (Exception | Error failure) { depth.close(); throw failure; }
        }
        public boolean depthClosed() { return depth.isClosed(); }
        @Override public void close() throws Exception {
            if (closed) return;
            closed = true;
            try {
                if (net.minecraft.client.Minecraft.getInstance() != minecraft || minecraft.getMainRenderTarget() != target)
                    throw new AssertionError("LIVE_MAIN_TARGET_SCOPE_REPLACED");
                set(net.minecraft.client.Minecraft.class, minecraft, "mainRenderTarget", previous);
                if (minecraft.getMainRenderTarget() != previous) throw new AssertionError("LIVE_MAIN_TARGET_RESTORATION");
            } finally { depth.close(); }
            if (!depth.isClosed()) throw new AssertionError("LIVE_MAIN_TARGET_DEPTH_CLOSE");
        }
    }
    private static final java.util.List<Runnable> fences = new java.util.ArrayList<>();
    public static void queueFencedTask(Runnable completion) { fences.add(completion); }
    public static void completeConsumers() {
        var submitted = java.util.List.copyOf(fences); fences.clear(); submitted.forEach(Runnable::run);
    }
    public static final class Texture extends com.mojang.blaze3d.textures.GpuTexture {
        private boolean closed;
        Texture(int usage, String name, com.mojang.blaze3d.textures.TextureFormat format, int width, int height, int layers, int levels) {
            super(usage, name, format, width, height, layers, levels);
        }
        @Override public void close() { if (closed) throw new AssertionError("LIVE_DUPLICATE_TEXTURE_CLOSE"); closed = true; }
        @Override public boolean isClosed() { return closed; }
    }
    public static final class View extends com.mojang.blaze3d.textures.GpuTextureView {
        private boolean closed;
        View(com.mojang.blaze3d.textures.GpuTexture texture) { super(texture, 0, 1); }
        @Override public void close() { if (closed) throw new AssertionError("LIVE_DUPLICATE_VIEW_CLOSE"); closed = true; }
        @Override public boolean isClosed() { return closed; }
    }
    public static com.mojang.blaze3d.systems.GpuDevice getDevice() {
        deviceReads++;
        return (com.mojang.blaze3d.systems.GpuDevice) java.lang.reflect.Proxy.newProxyInstance(SubmissionRawInputs.class.getClassLoader(),
                new Class<?>[]{com.mojang.blaze3d.systems.GpuDevice.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "createTexture" -> new Texture((Integer) args[1], ((java.util.function.Supplier<?>) args[0]).get().toString(),
                            (com.mojang.blaze3d.textures.TextureFormat) args[2], (Integer) args[3], (Integer) args[4], (Integer) args[5], (Integer) args[6]);
                    case "createTextureView" -> new View((com.mojang.blaze3d.textures.GpuTexture) args[0]);
                    case "createCommandEncoder" -> java.lang.reflect.Proxy.newProxyInstance(SubmissionRawInputs.class.getClassLoader(),
                            new Class<?>[]{com.mojang.blaze3d.systems.CommandEncoder.class}, (encoder, operation, values) -> {
                                if (!operation.getName().equals("clearColorAndDepthTextures")) throw new AssertionError("LIVE_UNKNOWN_ENCODER " + operation);
                                return null;
                            });
                    default -> throw new AssertionError("LIVE_UNKNOWN_DEVICE " + method);
                });
    }
    public static com.mojang.blaze3d.systems.SamplerCache getSamplerCache() {
        return new com.mojang.blaze3d.systems.SamplerCache() {
            @Override public com.mojang.blaze3d.textures.GpuSampler getRepeat(com.mojang.blaze3d.textures.FilterMode filter) { return null; }
        };
    }
    public static final class Projection {
        public Projection(String label, float near, float far, boolean flip) {}
        public com.mojang.blaze3d.buffers.GpuBufferSlice getBuffer(float width, float height) { return null; }
        public void close() {}
    }
    public static void setProjectionMatrix(com.mojang.blaze3d.buffers.GpuBufferSlice buffer, com.mojang.blaze3d.ProjectionType type) {}
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
