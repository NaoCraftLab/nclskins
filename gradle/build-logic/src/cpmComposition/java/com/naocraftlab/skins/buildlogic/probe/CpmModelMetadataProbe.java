package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.compat.cpm.CpmModelFileAdapter;
import com.naocraftlab.skins.client.CpmModelFileConversion;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.tom.cpl.util.Image;
import com.tom.cpl.math.Vec2i;
import com.tom.cpm.shared.io.*;
import com.tom.cpm.shared.parts.*;
import com.tom.cpm.shared.model.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@SuppressWarnings("deprecation")
public final class CpmModelMetadataProbe {
    private static IModelPart animation;
    private enum Unknown { A00,A01,A02,A03,A04,A05,A06,A07,A08,A09,A10,A11,A12,A13,A14,A15,A16,A17,A18,A19,A20,A21,A22,A23,A24,A25,A26,A27,A28,A29,A30,A31,A32,A33,A34,A35,A36,A37,A38,A39,A40,A41,A42,A43,A44,A45,A46,A47,A48,A49,A50,A51,A52,A53,A54,A55,A56,A57,A58,A59,A60,A61,A62,A63 }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static byte[] model(byte[] payload, boolean icon) throws Exception {
        var bytes = new ByteArrayOutputStream(); bytes.write(83);
        try (var checksum = new ChecksumOutputStream(bytes)) {
            var writer = new IOHelper(checksum);
            writer.writeUTF("Synthetic"); writer.writeUTF(""); writer.writeByteArray(payload);
            writer.writeByteArray(new byte[0]);
            if (icon) { var image = new Image(128,128); Arrays.fill(image.getData(),0xff111111); writer.writeImage(image); }
            else writer.writeVarInt(0);
        }
        return bytes.toByteArray();
    }
    private static byte[] payload(boolean owned, boolean modern, SkinType type, int width) throws Exception {
        var image = new Image(width, width);
        Arrays.fill(image.getData(), 0xff12ab34); image.setRGB(width - 1, width - 1, 0xff987654);
        var parts = new ArrayList<IModelPart>();
        if (owned || modern) {
            var body = new IOHelper();
            if (modern) body.writeEnum(owned ? TextureSheetType.SKIN : TextureSheetType.CAPE);
            new com.tom.cpm.shared.skin.TextureProvider(image, new Vec2i(width, width)).write(body);
            parts.add(modern ? new ModelPartTexture(body.flip(), null) : new ModelPartSkin(body.flip(), null));
        }
        if (owned) {
            parts.add(new ModelPartUUIDLockout(UUID.fromString("00000000-0000-0000-0000-000000000123")));
            parts.add(animation);
        }
        var definition = new ModelPartDefinition(List.of()); definition.setOtherParts(modern ? List.of() : parts);
        var bytes = new ByteArrayOutputStream(); bytes.write(83);
        try (var checksum = new ChecksumOutputStream(bytes)) {
            var writer = new IOHelper(checksum);
            if (type != null) writer.writeObjectBlock(new ModelPartSkinType(type));
            writer.writeObjectBlock(definition);
            if (modern) for (var part : parts) writer.writeObjectBlock(part);
            if (owned) try (var unknown = writer.writeNextObjectBlock(Unknown.A63)) { unknown.write(new byte[]{17,35,57,91}); }
            writer.writeObjectBlock(ModelPartEnd.END);
        }
        return bytes.toByteArray();
    }
    private static int[] bundled(SkinModel model) throws Exception {
        try (var input = CpmModelMetadataProbe.class.getResourceAsStream("/assets/cpm/textures/template/" + (model == SkinModel.SLIM ? "slim" : "default") + ".png")) {
            var image = javax.imageio.ImageIO.read(input);
            return image.getRGB(0,0,64,64,null,0,64);
        }
    }
    private static void verify(byte[] raw, byte[] png, SkinVariant variant) throws Exception {
        var decoded = javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
        var image = new Image(decoded.getRGB(0,0,64,64,null,0,64),64);
        var actual = new SkinDataInputStream(image,com.tom.cpm.shared.MinecraftClientAccess.get().getDefinitionLoader().getTemplate(),
                variant == SkinVariant.SLIM ? 0 : 1).readNBytes(raw.length);
        check(Arrays.equals(raw,actual), "Exact independent raw payload/channel including unknown sibling and complete atlas");
        var reader = new IOHelper(new ByteArrayInputStream(actual,1,actual.length-1));
        boolean lock = false, animated = false;
        while (true) {
            var part = reader.readObjectBlock(ModelPartType.VALUES,(type,block)->type.getFactory().create(block,null));
            if (part instanceof ModelPartDefinition || part instanceof ModelPartTexture || part instanceof ModelPartUUIDLockout || part instanceof ModelPartAnimation) {
                var imageParts = part instanceof ModelPartDefinition
                        ? ((com.naocraftlab.skins.compat.cpm.mixin.CpmModelDefinitionPartsAccessor)part).nclskins$modelParts() : List.of(part);
                for (var child:imageParts) {
                    if (child instanceof ModelPartUUIDLockout) {
                        var writer = new IOHelper(); child.write(writer);
                        check(writer.flip().readUUID().equals(UUID.fromString("00000000-0000-0000-0000-000000000123")), "Exact authored UUID lock retained without resolve/apply");
                        lock = true; continue;
                    }
                    if (child instanceof ModelPartAnimation) { animated = true; continue; }
                    com.tom.cpm.shared.skin.TextureProvider provider=child instanceof ModelPartSkin
                            ? ((com.naocraftlab.skins.compat.cpm.mixin.CpmModelSkinTextureAccessor)child).nclskins$modelSkin()
                            : ((com.naocraftlab.skins.compat.cpm.mixin.CpmModelTextureAccessor)child).nclskins$modelTexture();
                    check(provider.getImage().getWidth()==128 && provider.getImage().getHeight()==128
                            && provider.getImage().getRGB(127,127)==0xff987654, "Complete native atlas far corner never cropped to vanilla");
                }
            }
            if (part instanceof ModelPartEnd) break;
        }
        check(lock && animated, "Native UUID lock and actual custom animation remain in the complete payload");
    }
    private static void controls(Path fixtures,Path output) throws Exception {
        var adapter=new CpmModelFileAdapter();
        for (boolean modern:List.of(false,true)) {
            byte[] bytes=model(payload(false,modern,SkinType.DEFAULT,128),true);
            Path source=Files.write(output.resolve("borrowed-"+modern+".cpmmodel"),bytes);
            try (var borrowed=adapter.admit(new ByteArrayInputStream(bytes))) {
                check(borrowed.automaticBase(SkinModel.SLIM).isEmpty(), "Icon/cape-only native model never supplies an automatic SKIN");
            }
            com.naocraftlab.skins.runtime.CpmCallerHarness.saveThroughRuntime(adapter,Files.readAllBytes(fixtures.resolve("classic-base.png")),source,
                    fixtures.resolve("classic-base.png"),output.resolve("borrowed-library-"+modern),SkinVariant.CLASSIC,"borrowed");
            check(Arrays.equals(bytes,Files.readAllBytes(source)),"Borrowed/icon original source unchanged");
        }
        var foreignBody=new IOHelper();
        var foreignImage=new Image(128,128);Arrays.fill(foreignImage.getData(),0xff223344);
        new com.tom.cpm.shared.skin.TextureProvider(foreignImage,new Vec2i(128,128)).write(foreignBody);
        var foreignPart=new ModelPartSkin(foreignBody.flip(),null);
        var foreignDefinition=new ModelPartDefinition(List.of());foreignDefinition.setOtherParts(new ArrayList<>(List.of(foreignPart)));
        var foreignList=((com.naocraftlab.skins.compat.cpm.mixin.CpmModelDefinitionPartsAccessor)foreignDefinition).nclskins$modelParts();
        var foreignProvider=((com.naocraftlab.skins.compat.cpm.mixin.CpmModelSkinTextureAccessor)foreignPart).nclskins$modelSkin();
        var foreignPixels=foreignProvider.getImage().getData().clone();
        byte[] ownedBytes=model(payload(true,false,SkinType.UNKNOWN,128),false);
        var first=adapter.admit(new ByteArrayInputStream(ownedBytes));
        try (var second=adapter.admit(new ByteArrayInputStream(model(payload(false,false,null,64),false)))) {
            check(second.automaticBase(SkinModel.SLIM).isEmpty(),"Two handles never share owned texture evidence");
            first.close();
            try { first.automaticBase(SkinModel.SLIM); throw new AssertionError("Closed metadata reused"); }
            catch (CpmModelFileConversion.Failure expected) { check(expected.reason()==CpmModelFileConversion.Reason.CLOSED,"Exact closed reason"); }
            check(second.automaticBase(SkinModel.CLASSIC).isEmpty(),"Closing sibling preserves its metadata");
            try { new CpmModelFileAdapter().prepare(second,new CpmModelFileConversion.Raster(SkinModel.CLASSIC,bundled(SkinModel.CLASSIC)),1); throw new AssertionError("Foreign handle admitted"); }
            catch (CpmModelFileConversion.Failure expected) { check(expected.reason()==CpmModelFileConversion.Reason.CLOSED,"Foreign owner typed closed"); }
        }
        check(((com.naocraftlab.skins.compat.cpm.mixin.CpmModelDefinitionPartsAccessor)foreignDefinition).nclskins$modelParts()==foreignList
                && foreignList.equals(List.of(foreignPart)) && Arrays.equals(foreignPixels,foreignProvider.getImage().getData()), "Foreign native list/provider identity and pixels remain unchanged");
        var body=new IOHelper();body.write2s(new Vec2i(64,64));body.writeVarInt(0);
        var raw=new ByteArrayOutputStream();raw.write(83);
        try(var checksum=new ChecksumOutputStream(raw)) {
            var writer=new IOHelper(checksum);
            try(var block=writer.writeNextObjectBlock(ModelPartType.SKIN)){block.write(body.toBytes());}
            writer.writeObjectBlock(ModelPartEnd.END);
        }
        try(var ignored=adapter.admit(new ByteArrayInputStream(model(raw.toByteArray(),false)))){throw new AssertionError("Empty native SKIN must fail, not borrowed");}
        catch(CpmModelFileConversion.Failure expected){check(expected.reason()==CpmModelFileConversion.Reason.INVALID_MODEL,"Empty native image typed invalid");}
        raw.reset();raw.write(83);
        try(var checksum=new ChecksumOutputStream(raw)) {
            var writer=new IOHelper(checksum);
            try(var block=writer.writeNextObjectBlock(ModelPartType.SKIN)) {
                block.write2s(new Vec2i(64,64));block.writeByteArray(new byte[]{1,2,3});
            }
            writer.writeObjectBlock(ModelPartEnd.END);
        }
        try(var ignored=adapter.admit(new ByteArrayInputStream(model(raw.toByteArray(),false)))) { throw new AssertionError("Malformed native image became borrowed"); }
        catch(CpmModelFileConversion.Failure expected) { check(expected.reason()==CpmModelFileConversion.Reason.INVALID_MODEL || expected.reason()==CpmModelFileConversion.Reason.CPM_UNAVAILABLE,"Malformed native image has safe typed failure"); }
        System.out.println("PASS borrowed/cape-only/icon actual chooser; two handles/foreign owner/close/stale/empty and malformed native image");
    }
    public static void main(String[] args) throws Exception {
        Path fixtures=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);
        CpmModelConversionProbe.main(new String[]{fixtures.toString(),output.resolve("host").toString(),"environment-only"});
        byte[] animated = Files.readAllBytes(fixtures.resolveSibling("project-binding/animation.payload"));
        var animationReader = new IOHelper(new ByteArrayInputStream(animated,1,animated.length-1));
        while (true) {
            var part = animationReader.readObjectBlock(ModelPartType.VALUES,(type,block)->type.getFactory().create(block,null));
            if (part instanceof ModelPartDefinition) animation = ((com.naocraftlab.skins.compat.cpm.mixin.CpmModelDefinitionPartsAccessor)part).nclskins$modelParts()
                    .stream().filter(ModelPartAnimation.class::isInstance).findFirst().orElse(null);
            if (part instanceof ModelPartEnd) break;
        }
        check(animation != null, "Independent retained upstream custom-animation fixture present");
        byte[] ordinary=Files.readAllBytes(fixtures.resolve("classic-base.png"));
        new com.naocraftlab.skins.runtime.CpmCallerHarness(new CpmModelFileAdapter(),ordinary).ordinary(fixtures.resolve("classic-base.png"));
        controls(fixtures,output);
        int count=0;
        for(boolean modern:List.of(false,true)) for(SkinType type:new SkinType[]{SkinType.DEFAULT,SkinType.SLIM,SkinType.UNKNOWN,null})
        for(boolean url:List.of(false,true)) {
            String name=(modern?"modern":"legacy")+"-"+type+"-"+url;
            Path dir=Files.createDirectories(output.resolve(name));
            byte[] raw=payload(true,modern,type,128),sourceBytes=model(raw,false);
            Path source=Files.write(dir.resolve("source.cpmmodel"),sourceBytes);
            check(Arrays.equals(raw,ModelFile.load("independent",new ByteArrayInputStream(sourceBytes)).getDataBlock()),"Healthy native file fixture control");
            var variant=type==SkinType.DEFAULT?SkinVariant.CLASSIC:SkinVariant.SLIM;
            var selected=SkinModel.valueOf(variant.name());
            var adapter=new CpmModelFileAdapter();
            try(var admitted=adapter.admit(new ByteArrayInputStream(sourceBytes))) {
                check(admitted.requiredVariant().equals(type==null||type==SkinType.UNKNOWN?Optional.empty():Optional.of(selected)),"Native concrete/UNKNOWN metadata is not invented");
                var base=admitted.automaticBase(SkinModel.SLIM).orElseThrow();
                check(base.variant()==selected && Arrays.equals(base.argb(),bundled(selected)),"Independent type-specific bundled fallback; UNKNOWN preferred Slim is not Classic or atlas");
                if(type==null||type==SkinType.UNKNOWN)check(Arrays.equals(admitted.automaticBase(SkinModel.CLASSIC).orElseThrow().argb(),bundled(SkinModel.CLASSIC)),"UNKNOWN actual Classic alternate has its own bundled base");
            }
            String hash=com.naocraftlab.skins.runtime.CpmCallerHarness.saveThroughRuntime(adapter,ordinary,source,
                    fixtures.resolve("classic-base.png"),dir.resolve("library"),variant,(url?"url-":"")+"automatic"+(type==null||type==SkinType.UNKNOWN?"-pairs":""));
            var storage=new com.naocraftlab.skins.core.storage.NclSkinsStorage(dir.resolve("library"),new com.naocraftlab.skins.core.png.PngValidator(),java.time.Clock.systemUTC());
            byte[] saved=storage.readAsset(hash);verify(raw,saved,variant);
            check(Arrays.equals(sourceBytes,Files.readAllBytes(source)),"Raw source remains byte-exact");Files.delete(source);
            byte[] applied=com.naocraftlab.skins.runtime.CpmCallerHarness.explicitTestApply(dir.resolve("library"),ordinary,variant);
            check(Arrays.equals(saved,applied),"Source-free actual application preserves automatic carrier");verify(raw,applied,variant);
            System.out.println("PASS automatic native caller/Save/application "+name);count++;
        }
        Path stale=Files.write(output.resolve("stale.cpmmodel"),model(payload(true,false,SkinType.UNKNOWN,128),false));
        com.naocraftlab.skins.runtime.CpmCallerHarness.saveThroughRuntime(new CpmModelFileAdapter(),ordinary,stale,
                fixtures.resolve("classic-base.png"),output.resolve("stale-library"),SkinVariant.SLIM,"automatic-stale");
        System.out.println("PASS held automatic completion/close cannot publish stale Editor");
        System.out.println("PASS automatic native groups="+count);
    }
}
