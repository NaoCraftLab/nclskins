package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.compat.client.identifier.measurement.AvatarBodyCapture;
import com.naocraftlab.skins.compat.cpm.CpmIntegration;
import com.tom.cpm.client.PlayerRenderStateAccess;
import com.tom.cpm.shared.config.Player;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.resources.Identifier;

/** Shared source/CPM/texture orchestration; ABI-specific leaves only submit and drain. */
public final class CpmNativeMeasurementCore {
    @FunctionalInterface public interface Render { void draw(Player<?> player, AvatarRenderState state) throws Exception; }
    public static boolean measure(CpmExtractionBodyProbe.Avatar raw,boolean live,TextureManager textures,
            PlayerModel model,Set<String> installed,Render render) {
        if(!AvatarBodyCapture.excludes(raw)&&!live)return false;
        try {
            Player<?> player=(Player<?>)CpmExtractionBodyProbe.measurementPlayers.get(CpmExtractionBodyProbe.AccessoryProjection.profile);
            if(player==null)throw new AssertionError("measurement selects original owned cached player");
            var source=CpmIntegration.source(player);
            if(source==null||!source.active()||!source.location().equals(raw.appearance.skin().location())||source.model()!=raw.appearance.model())
                throw new AssertionError("measurement exact current source and variant");
            var state=(AvatarRenderState)(Object)raw;var id=Identifier.parse(source.location());
            if(installed.add(source.location())) {
                var image=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,64,64,source.pixels(),0,64);
                var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"PNG",out);
                var texture=new DynamicTexture(()->"owned synthetic measurement material",com.mojang.blaze3d.platform.NativeImage.read(new java.io.ByteArrayInputStream(out.toByteArray())));
                var field=TextureManager.class.getDeclaredField("byPath");field.setAccessible(true);
                @SuppressWarnings("unchecked") var map=(Map<Identifier,AbstractTexture>)field.get(textures);map.put(id,texture);
            }
            state.skin=new net.minecraft.world.entity.player.PlayerSkin(new net.minecraft.core.ClientAsset.DownloadedTexture(id,"synthetic:no-network"),null,null,net.minecraft.world.entity.player.PlayerModelType.WIDE,false);
            state.scale=1;state.pose=net.minecraft.world.entity.Pose.STANDING;
            state.showHat=state.showJacket=state.showLeftSleeve=state.showRightSleeve=state.showLeftPants=state.showRightPants=true;
            var access=(PlayerRenderStateAccess)(Object)state;access.cpm$storeState(model);access.cpm$setPlayer((Player)player);
            var animation=new com.tom.cpm.shared.animation.AnimationState(com.tom.cpm.shared.animation.AnimationEngine.AnimationMode.PLAYER);
            animation.syncState=new com.tom.cpm.shared.animation.ServerAnimationState();access.cpm$setAnimationState(animation);
            render.draw(player,state);return true;
        } catch(Exception failure) { throw new AssertionError(failure); }
    }
    public static void stateBinding(Path root) throws Exception {
        String name="com/naocraftlab/skins/buildlogic/probe/CpmExtractionBodyProbe$State";
        var writer=new org.objectweb.asm.ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V17,org.objectweb.asm.Opcodes.ACC_PUBLIC,name,null,"net/minecraft/client/renderer/entity/state/AvatarRenderState",null);
        var init=writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC,"<init>","()V",null,null);
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD,0);init.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL,"net/minecraft/client/renderer/entity/state/AvatarRenderState","<init>","()V",false);init.visitInsn(org.objectweb.asm.Opcodes.RETURN);init.visitMaxs(1,1);init.visitEnd();writer.visitEnd();
        var file=root.resolve(name+".class");Files.createDirectories(file.getParent());Files.write(file,writer.toByteArray());
    }
}
