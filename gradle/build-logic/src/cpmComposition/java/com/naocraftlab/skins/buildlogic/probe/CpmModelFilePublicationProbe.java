package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.CpmModelFileConversion;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.compat.cpm.CpmModelFileAdapter;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.tom.cpl.math.Vec2i;
import com.tom.cpl.util.Image;
import com.tom.cpm.shared.definition.Link;
import com.tom.cpm.shared.io.*;
import com.tom.cpm.shared.model.*;
import com.tom.cpm.shared.parts.*;
import java.io.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;

@SuppressWarnings("deprecation")
public final class CpmModelFilePublicationProbe {
    private static boolean nestedLocal;
    private enum Unknown { A00,A01,A02,A03,A04,A05,A06,A07,A08,A09,A10,A11,A12,A13,A14,A15,A16,A17,A18,A19,A20,A21,A22,A23,A24,A25,A26,A27,A28,A29,A30,A31,A32,A33,A34,A35,A36,A37,A38,A39,A40,A41,A42,A43,A44,A45,A46,A47,A48,A49,A50,A51,A52,A53,A54,A55,A56,A57,A58,A59,A60,A61,A62,A63 }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static ModelPartTexture texture() throws Exception {
        var image = new Image(64,64); Arrays.fill(image.getData(), 0xff123456);
        var body = new IOHelper(); body.writeEnum(TextureSheetType.SKIN);
        new com.tom.cpm.shared.skin.TextureProvider(image, new Vec2i(64,64)).write(body);
        return new ModelPartTexture(body.flip(), null);
    }
    private static byte[] payload(SkinType type, boolean local, byte[] opaque, boolean packageWire, byte[] definition) throws Exception {
        return payload(type, local, opaque, packageWire, definition, true);
    }
    private static byte[] payload(SkinType type, boolean local, byte[] opaque, boolean packageWire, byte[] definition, boolean includeType) throws Exception {
        var bytes = new ByteArrayOutputStream(); bytes.write(83);
        try (var checksum = new ChecksumOutputStream(bytes)) {
            var writer = new IOHelper(checksum);
            if (includeType) writer.writeObjectBlock(new ModelPartSkinType(type));
            if (!local) writer.writeObjectBlock(texture());
            if (definition != null) try (var block = writer.writeNextObjectBlock(ModelPartType.DEFINITION)) {
                block.write(Arrays.copyOfRange(definition, 1, definition.length - 2));
            }
            writer.writeObjectBlock(new ModelPartUUIDLockout(UUID.fromString("00000000-0000-0000-0000-000000000123")));
            if (local) writer.writeObjectBlock(packageWire ? new ModelPartCollection().toLink(new Link("local:synthetic"))
                    : new ModelPartDefinitionLink(new Link("local:synthetic")));
            try (var block = writer.writeNextObjectBlock(Unknown.A63)) { block.write(opaque); }
            writer.writeObjectBlock(ModelPartEnd.END);
        }
        return bytes.toByteArray();
    }
    private static byte[] backup(boolean packageWire, int opaqueSize) throws Exception {
        var bytes = new ByteArrayOutputStream(); bytes.write(83);
        try (var checksum = new ChecksumOutputStream(bytes)) {
            var writer = new IOHelper(checksum);
            if (!packageWire) writer.writeVarInt(0);
            writer.writeObjectBlock(texture());
            if (nestedLocal) writer.writeObjectBlock(new ModelPartCollection().toLink(new Link("local:other")));
            try (var block = writer.writeNextObjectBlock(Unknown.A63)) { block.write(new byte[opaqueSize]); }
            writer.writeObjectBlock(ModelPartEnd.END);
        }
        return bytes.toByteArray();
    }
    private static byte[] model(byte[] payload, byte[] backup) throws Exception {
        var bytes = new ByteArrayOutputStream(); bytes.write(83);
        try (var checksum = new ChecksumOutputStream(bytes)) {
            var writer = new IOHelper(checksum);
            writer.writeUTF("Synthetic"); writer.writeUTF(""); writer.writeByteArray(payload);
            writer.writeByteArray(backup);
            if (backup.length != 0) new Link("local:synthetic").write(writer);
            writer.writeVarInt(0);
        }
        return bytes.toByteArray();
    }
    private static Object readWire(ModelPartLink link, byte[] bytes) throws Exception {
        var load = link.getClass().getDeclaredMethod("load", IOHelper.class, com.tom.cpm.shared.definition.ModelDefinition.class);
        load.setAccessible(true);
        var wire = new ByteArrayInputStream(bytes);
        check(wire.read() == 83, "Independent native wire header");
        var checksum = new ChecksumInputStream(wire);
        Object result;
        try { result = load.invoke(link, new IOHelper(checksum), null); }
        catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof IOException io) throw io;
            throw failure;
        }
        checksum.checkSum();
        check(wire.available() == 0, "Native reader consumes complete wire");
        return result;
    }

    private static void wrongKind(byte[] wire, boolean definition) throws Exception {
        ModelPartLink wrong = definition ? (ModelPartLink) new ModelPartCollection().toLink(new Link("p:synthetic"))
                : new ModelPartDefinitionLink(new Link("p:synthetic"));
        try {
            readWire(wrong, wire);
            throw new AssertionError("Wrong-kind native reader unexpectedly accepts wire");
        } catch (IOException expected) {
            System.out.println("PASS wrong-kind native sensitivity " + wrong.getType() + " IOException");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            check(expected.getCause() instanceof RuntimeException, "Wrong-kind failure originates in native payload reader, not reflection setup");
            System.out.println("PASS wrong-kind native sensitivity " + wrong.getType() + " " + expected.getCause().getClass().getSimpleName());
        }
    }

    private static ModelPartLink carrierLink(byte[] png, SkinType type) throws Exception {
        var decoded = javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
        var image = new Image(decoded.getRGB(0, 0, 64, 64, null, 0, 64), 64);
        var stream = new SkinDataInputStream(image, com.tom.cpm.shared.MinecraftClientAccess.get().getDefinitionLoader().getTemplate(), type.getChannel());
        check(stream.read() == 83, "Fresh-process native channel header");
        var reader = new IOHelper(stream);
        ModelPartLink found = null;
        while (true) {
            var part = reader.readObjectBlock(ModelPartType.VALUES, (kind, block) -> kind.getFactory().create(block, null));
            if (part instanceof ModelPartLink link) {
                check(found == null, "Expected exactly one publication link");
                found = link;
            }
            if (part instanceof ModelPartEnd) return found;
        }
    }

    private static void freshApply(Path fixtures, Path output) throws Exception {
        check(ProcessHandle.current().pid() != Long.parseLong(Files.readString(output.resolve("parent.pid"))), "Genuinely separate JVM");
        for (SkinVariant variant : SkinVariant.values()) {
            Path dir = output.resolve(variant.name());
            check(!Files.exists(dir.resolve("source.cpmmodel")) && !Files.exists(dir.resolve("base.png")), "Fresh JVM has no original model or selected base");
            byte[] expected = Files.readAllBytes(dir.resolve("expected.png"));
            String hash = Files.readString(dir.resolve("expected.sha256"));
            var storage = new NclSkinsStorage(dir.resolve("library"), new PngValidator(), Clock.systemUTC());
            byte[] saved = storage.readAsset(hash);
            check(Arrays.equals(expected, saved), "Fresh process reads exact carrier from immutable storage");
            byte[] uploaded = com.naocraftlab.skins.runtime.CpmCallerHarness.explicitTestApply(dir.resolve("library"),
                    Files.readAllBytes(fixtures.resolve("classic-base.png")), variant);
            check(Arrays.equals(expected, uploaded), "Fresh process actual application upload equals independently retained carrier");
            SkinType type = variant == SkinVariant.SLIM ? SkinType.SLIM : SkinType.DEFAULT;
            var actual = javax.imageio.ImageIO.read(new ByteArrayInputStream(uploaded));
            var pixels = new Image(actual.getRGB(0, 0, 64, 64, null, 0, 64), 64);
            byte[] payload = Files.readAllBytes(dir.resolve("expected.payload"));
            byte[] readback = new SkinDataInputStream(pixels, com.tom.cpm.shared.MinecraftClientAccess.get().getDefinitionLoader().getTemplate(), type.getChannel()).readNBytes(payload.length);
            check(Arrays.equals(payload, readback), "Fresh application native channel and independent payload preserved");
            if (Files.exists(dir.resolve("expected.wire"))) {
                var link = carrierLink(uploaded, type);
                check(link != null && link.getLink().toString().equals("p:synthetic"), "Fresh carrier keeps confirmed synthetic link");
                check(link.getType().name().equals(Files.readString(dir.resolve("expected.kind"))), "Fresh application preserves correct link kind");
                check(readWire(link, Files.readAllBytes(dir.resolve("expected.wire"))) != null, "Fresh native reader accepts saved carrier wire kind");
            }
            System.out.println("PASS fresh-JVM ModelFile Save/source-deletion/reopen/actual-Apply " + variant);
        }
    }

    private static void repeatedLocalLinks(SkinModel variant, Path fixtures, Path output) throws Exception {
        SkinType type = variant == SkinModel.SLIM ? SkinType.SLIM : SkinType.DEFAULT;
        for (boolean packageWire : new boolean[]{false, true}) for (boolean small : new boolean[]{false, true}) {
            byte[] wire = backup(packageWire, small ? 9 : 8192);
            var local = packageWire ? new ModelPartCollection().toLink(new Link("local:synthetic"))
                    : new ModelPartDefinitionLink(new Link("local:synthetic"));
            var confirmed = packageWire ? new ModelPartCollection().toLink(new Link("p:synthetic"))
                    : new ModelPartDefinitionLink(new Link("p:synthetic"));
            check(readWire((ModelPartLink) confirmed, wire) != null, "Repeated same-kind occurrences share one valid backup wire");
            byte[][] data = new byte[2][];
            for (int i = 0; i < data.length; i++) {
                var bytes = new ByteArrayOutputStream(); bytes.write(83);
                try (var checksum = new ChecksumOutputStream(bytes)) {
                    var writer = new IOHelper(checksum);
                    writer.writeObjectBlock(new ModelPartSkinType(type));
                    for (int occurrence = 0; occurrence < 2; occurrence++) {
                        if (i == 0 || !small) writer.writeObjectBlock(i == 0 ? local : confirmed);
                        else if (packageWire) {
                            writer.writeObjectBlock(texture());
                            try (var block = writer.writeNextObjectBlock(Unknown.A63)) { block.write(new byte[9]); }
                        } else try (var block = writer.writeNextObjectBlock(ModelPartType.DEFINITION)) {
                            block.write(Arrays.copyOfRange(wire, 1, wire.length - 2));
                        }
                        if (occurrence == 0) writer.writeObjectBlock(new ModelPartUUIDLockout(UUID.fromString("00000000-0000-0000-0000-000000000123")));
                    }
                    writer.writeObjectBlock(ModelPartEnd.END);
                }
                data[i] = bytes.toByteArray();
            }
            var image = new Image(type.getSkinTexture().getData().clone(), 64);
            var template = com.tom.cpm.shared.MinecraftClientAccess.get().getDefinitionLoader().getTemplate();
            try (var writer = new SkinDataOutputStream(image, template, type.getChannel())) { writer.write(data[1]); }
            check(Arrays.equals(data[1], new SkinDataInputStream(image, template, type.getChannel()).readNBytes(data[1].length)), "One confirmed wire can replace both occurrences without collapsing multiplicity");
            var adapter = new CpmModelFileAdapter();
            Path dir = output.resolve("repeated-" + variant + "-" + packageWire + "-" + small); Files.createDirectories(dir);
            Path source = Files.write(dir.resolve("source.cpmmodel"), model(data[0], wire));
            Path base = Files.copy(fixtures.resolve(variant == SkinModel.SLIM ? "slim-base.png" : "classic-base.png"), dir.resolve("base.png"));
            String hash = com.naocraftlab.skins.runtime.CpmCallerHarness.saveThroughRuntime(adapter, Files.readAllBytes(fixtures.resolve("classic-base.png")),
                    source, base, dir.resolve("library"), SkinVariant.valueOf(variant.name()), small ? "automatic" : "publication");
            byte[] saved = new NclSkinsStorage(dir.resolve("library"), new PngValidator(), Clock.systemUTC()).readAsset(hash);
            var decoded = javax.imageio.ImageIO.read(new ByteArrayInputStream(saved));
            check(Arrays.equals(data[1], new SkinDataInputStream(new Image(decoded.getRGB(0,0,64,64,null,0,64),64), template, type.getChannel()).readNBytes(data[1].length)),
                    "Real caller saves both repeated link occurrences without a duplicate upload");
            try (var admitted = adapter.admit(new ByteArrayInputStream(model(data[0], wire)))) {
                var prepared = adapter.prepare(admitted, admitted.automaticBase(variant).orElseThrow(), 29);
                if (small) {
                    check(prepared instanceof CpmModelFileConversion.Inline, "Fitting repeated local backups need no publication");
                    continue;
                }
                check(prepared instanceof CpmModelFileConversion.Publication, "Repeated matched backup has one real publication route");
                try (var publication = (CpmModelFileConversion.Publication) prepared) {
                    check(Arrays.equals(wire, publication.wire()), "Every repeated occurrence shares exact backup bytes");
                    var inline = (CpmModelFileConversion.Inline) publication.link("synthetic");
                    var actual = new Image(inline.raster().argb(), 64);
                    check(Arrays.equals(data[1], new SkinDataInputStream(actual, template, type.getChannel()).readNBytes(data[1].length)),
                            "Actual publication replaces every occurrence preserving exact order and multiplicity");
                }
            }
        }
    }

    private static void repeatedDefinitions(SkinModel variant, Path fixtures, Path output) throws Exception {
        SkinType type = variant == SkinModel.SLIM ? SkinType.SLIM : SkinType.DEFAULT;
        byte[] definition = backup(false, 8192);
        byte[][] payloads = new byte[2][];
        for (int version = 0; version < 2; version++) {
            var bytes = new ByteArrayOutputStream(); bytes.write(83);
            try (var checksum = new ChecksumOutputStream(bytes)) {
                var writer = new IOHelper(checksum); writer.writeObjectBlock(new ModelPartSkinType(type));
                for (int occurrence = 0; occurrence < 2; occurrence++) {
                    if (version == 0) try (var block = writer.writeNextObjectBlock(ModelPartType.DEFINITION)) {
                        block.write(Arrays.copyOfRange(definition, 1, definition.length - 2));
                    } else writer.writeObjectBlock(new ModelPartDefinitionLink(new Link("p:synthetic")));
                    if (occurrence == 0) writer.writeObjectBlock(texture());
                }
                writer.writeObjectBlock(ModelPartEnd.END);
            }
            payloads[version] = bytes.toByteArray();
        }
        Path dir = output.resolve("definitions-" + variant); Files.createDirectories(dir);
        Path source = Files.write(dir.resolve("source.cpmmodel"), model(payloads[0], new byte[0]));
        Path base = Files.copy(fixtures.resolve(variant == SkinModel.SLIM ? "slim-base.png" : "classic-base.png"), dir.resolve("base.png"));
        String hash = com.naocraftlab.skins.runtime.CpmCallerHarness.saveThroughRuntime(new CpmModelFileAdapter(),
                Files.readAllBytes(fixtures.resolve("classic-base.png")), source, base, dir.resolve("library"), SkinVariant.valueOf(variant.name()), "publication");
        check(Arrays.equals(definition, Files.readAllBytes(dir.resolve("library/publication.wire"))), "Repeated raw definitions use one exact confirmed wire");
        var saved = new NclSkinsStorage(dir.resolve("library"), new PngValidator(), Clock.systemUTC()).readAsset(hash);
        var image = javax.imageio.ImageIO.read(new ByteArrayInputStream(saved));
        var stream = new SkinDataInputStream(new Image(image.getRGB(0,0,64,64,null,0,64),64),
                com.tom.cpm.shared.MinecraftClientAccess.get().getDefinitionLoader().getTemplate(), type.getChannel());
        check(Arrays.equals(payloads[1], stream.readNBytes(payloads[1].length)), "Multiple raw definitions retain distinct positions while common caller reuses confirmed wire");
    }

    private static void orderedSpans(SkinModel variant, Path fixtures, Path output) throws Exception {
        SkinType type = variant == SkinModel.SLIM ? SkinType.SLIM : SkinType.DEFAULT;
        byte[] definition = backup(false, 8192);
        byte[] before = new byte[8192], after = new byte[8192];
        new Random(31).nextBytes(before); new Random(32).nextBytes(after);
        byte[][] packages = new byte[2][];
        for (int i = 0; i < 2; i++) {
            var bytes = new ByteArrayOutputStream(); bytes.write(83);
            try (var checksum = new ChecksumOutputStream(bytes)) {
                var writer = new IOHelper(checksum);
                if (i == 0) writer.writeObjectBlock(texture());
                try (var block = writer.writeNextObjectBlock(Unknown.A63)) { block.write(i == 0 ? before : after); }
                writer.writeObjectBlock(ModelPartEnd.END);
            }
            packages[i] = bytes.toByteArray();
        }
        byte[][] payloads = new byte[2][];
        for (int version = 0; version < 2; version++) {
            var bytes = new ByteArrayOutputStream(); bytes.write(83);
            try (var checksum = new ChecksumOutputStream(bytes)) {
                var writer = new IOHelper(checksum); writer.writeObjectBlock(new ModelPartSkinType(type));
                if (version == 0) {
                    writer.writeObjectBlock(texture());
                    try (var block = writer.writeNextObjectBlock(Unknown.A63)) { block.write(before); }
                    try (var block = writer.writeNextObjectBlock(ModelPartType.DEFINITION)) { block.write(Arrays.copyOfRange(definition,1,definition.length-2)); }
                    try (var block = writer.writeNextObjectBlock(Unknown.A63)) { block.write(after); }
                } else {
                    writer.writeObjectBlock(new ModelPartCollection().toLink(new Link("p:step2")));
                    writer.writeObjectBlock(new ModelPartDefinitionLink(new Link("p:step1")));
                    writer.writeObjectBlock(new ModelPartCollection().toLink(new Link("p:step3")));
                }
                writer.writeObjectBlock(ModelPartEnd.END);
            }
            payloads[version] = bytes.toByteArray();
        }
        Path dir = output.resolve("ordered-spans-" + variant); Files.createDirectories(dir);
        Path source = Files.write(dir.resolve("source.cpmmodel"), model(payloads[0], new byte[0]));
        Path base = Files.copy(fixtures.resolve(variant == SkinModel.SLIM ? "slim-base.png" : "classic-base.png"),dir.resolve("base.png"));
        String hash = com.naocraftlab.skins.runtime.CpmCallerHarness.saveThroughRuntime(new CpmModelFileAdapter(),
                Files.readAllBytes(fixtures.resolve("classic-base.png")),source,base,dir.resolve("library"),SkinVariant.valueOf(variant.name()),"publication-multi-three");
        check(Arrays.equals(definition, Files.readAllBytes(dir.resolve("library/publication-0.wire"))), "Ordered spans preserve independent definition wire");
        for (int i = 0; i < 2; i++) {
            byte[] wire = Files.readAllBytes(dir.resolve("library/publication-" + (i + 1) + ".wire"));
            check(Arrays.equals(packages[i], wire), "Only contiguous compatible span is packaged without crossing definition");
            check(readWire((ModelPartLink) new ModelPartCollection().toLink(new Link("p:synthetic")), wire) != null, "Each actual uploaded span is accepted by native package reader");
        }
        var png = new NclSkinsStorage(dir.resolve("library"),new PngValidator(),Clock.systemUTC()).readAsset(hash);
        var image = javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
        var stream = new SkinDataInputStream(new Image(image.getRGB(0,0,64,64,null,0,64),64),
                com.tom.cpm.shared.MinecraftClientAccess.get().getDefinitionLoader().getTemplate(),type.getChannel());
        check(Arrays.equals(payloads[1],stream.readNBytes(payloads[1].length)), "Carrier preserves package/definition/package source order despite publication order");
        Files.delete(source); Files.delete(base);
        byte[] applied = com.naocraftlab.skins.runtime.CpmCallerHarness.explicitTestApply(dir.resolve("library"),
                Files.readAllBytes(fixtures.resolve("classic-base.png")), SkinVariant.valueOf(variant.name()));
        check(Arrays.equals(png, applied), "Source-free three-wire Save/reopen/actual Apply captures exact final carrier and variant");
        System.out.println("PASS native publication-multi-three ordered wires/source-free Save/reopen/actual Apply " + variant);
    }

    private static void topology(Path fixtures, Path output) throws Exception {
        byte[] definition = backup(false, 8192);
        byte[] opaque = new byte[8192]; new Random(91).nextBytes(opaque);
        var definitionLink = new ModelPartDefinitionLink(new Link("p:definition"));
        var packageLink = (ModelPartLink) new ModelPartCollection().toLink(new Link("p:siblings"));
        for (SkinModel variant : SkinModel.values()) {
            SkinType type = variant == SkinModel.SLIM ? SkinType.SLIM : SkinType.DEFAULT;
            byte[] mixed = payload(type, false, opaque, false, definition);
            try {
                readWire(packageLink, mixed);
                throw new AssertionError("PACKAGE_LINK must reject embedded DEFINITION");
            } catch (IOException expected) { }
            byte[] linked = payload(type, true, opaque, false, null);
            try {
                readWire(packageLink, linked);
                throw new AssertionError("PACKAGE_LINK must reject DEFINITION_LINK");
            } catch (IOException expected) { }
            var siblingBytes = new ByteArrayOutputStream(); siblingBytes.write(83);
            try (var checksum = new ChecksumOutputStream(siblingBytes)) {
                var writer = new IOHelper(checksum);
                writer.writeObjectBlock(new ModelPartUUIDLockout(UUID.fromString("00000000-0000-0000-0000-000000000123")));
                try (var block = writer.writeNextObjectBlock(Unknown.A63)) { block.write(opaque); }
                writer.writeObjectBlock(ModelPartEnd.END);
            }
            byte[] siblings = siblingBytes.toByteArray();
            var reconstructed = new ByteArrayOutputStream(); reconstructed.write(83);
            try (var checksum = new ChecksumOutputStream(reconstructed)) {
                var writer = new IOHelper(checksum);
                writer.writeObjectBlock(new ModelPartSkinType(type));
                writer.writeObjectBlock(texture());
                try (var block = writer.writeNextObjectBlock(ModelPartType.DEFINITION)) {
                    block.write(Arrays.copyOfRange(definition, 1, definition.length - 2));
                }
                writer.write(Arrays.copyOfRange(siblings, 1, siblings.length - 2));
            }
            check(Arrays.equals(mixed, reconstructed.toByteArray()), "Proposed partition expands to exact original raw model, including block order");
            check(readWire(definitionLink, definition) != null, "Split candidate original definition wire accepted natively");
            check(readWire(packageLink, siblings) != null, "Split candidate oversized raw sibling package accepted natively");
            var nativeReader = new IOHelper(new ByteArrayInputStream(siblings, 1, siblings.length - 3));
            boolean unknown = false, lock = false;
            while (true) {
                var tag = nativeReader.readObjectBlock(Unknown.values(), (kind, block) -> {
                    if (kind == Unknown.A63) check(Arrays.equals(opaque, block.getDin().readAllBytes()), "Split candidate keeps exact unknown sibling bytes");
                    if (kind.ordinal() == ModelPartType.UUID_LOCK.ordinal()) check(block.readUUID().equals(UUID.fromString("00000000-0000-0000-0000-000000000123")), "Split candidate preserves authored lock");
                    return kind;
                });
                unknown |= tag == Unknown.A63;
                lock |= tag.ordinal() == ModelPartType.UUID_LOCK.ordinal();
                if (tag.ordinal() == ModelPartType.END.ordinal()) break;
            }
            check(unknown && lock, "Unknown and UUID blocks retained in proposed second publication");
            var bytes = new ByteArrayOutputStream(); bytes.write(83);
            try (var checksum = new ChecksumOutputStream(bytes)) {
                var writer = new IOHelper(checksum);
                writer.writeObjectBlock(new ModelPartSkinType(type));
                writer.writeObjectBlock(texture());
                writer.writeObjectBlock(definitionLink);
                writer.writeObjectBlock(packageLink);
                writer.writeObjectBlock(ModelPartEnd.END);
            }
            var image = new Image(type.getSkinTexture().getData().clone(), 64);
            var template = com.tom.cpm.shared.MinecraftClientAccess.get().getDefinitionLoader().getTemplate();
            try (var writer = new SkinDataOutputStream(image, template, type.getChannel())) { writer.write(bytes.toByteArray()); }
            check(Arrays.equals(bytes.toByteArray(), new SkinDataInputStream(image, template, type.getChannel()).readNBytes(bytes.size())), "Two independently typed links fit native carrier without discarding payloads");
            var adapter = new CpmModelFileAdapter();
            Path dir = output.resolve(variant.name()); Files.createDirectories(dir);
            Path source = Files.write(dir.resolve("source.cpmmodel"), model(mixed, new byte[0]));
            Path base = Files.copy(fixtures.resolve(variant == SkinModel.SLIM ? "slim-base.png" : "classic-base.png"), dir.resolve("base.png"));
            for (String mode : List.of("publication-multi", "publication-multi-url", "publication-multi-cancel", "publication-multi-stale", "publication-multi-uncertain", "publication-multi-retry")) {
                Path library = dir.resolve(mode.equals("publication-multi") ? "library" : mode);
                String hash = com.naocraftlab.skins.runtime.CpmCallerHarness.saveThroughRuntime(adapter, Files.readAllBytes(fixtures.resolve("classic-base.png")),
                        source, base, library, SkinVariant.valueOf(variant.name()), mode);
                if (hash != null) {
                    check(Arrays.equals(definition, Files.readAllBytes(library.resolve("publication-0.wire"))), "Actual first upload equals independent definition wire");
                    check(Arrays.equals(siblings, Files.readAllBytes(library.resolve("publication-1.wire"))), "Actual second upload equals independent sibling package wire");
                    byte[] saved = new NclSkinsStorage(library, new PngValidator(), Clock.systemUTC()).readAsset(hash);
                    var expected = new ByteArrayOutputStream(); expected.write(83);
                    try (var checksum = new ChecksumOutputStream(expected)) {
                        var writer = new IOHelper(checksum);
                        writer.writeObjectBlock(new ModelPartSkinType(type)); writer.writeObjectBlock(texture());
                        writer.writeObjectBlock(new ModelPartDefinitionLink(new Link("p:step1")));
                        writer.writeObjectBlock(new ModelPartCollection().toLink(new Link("p:step2")));
                        writer.writeObjectBlock(ModelPartEnd.END);
                    }
                    var savedImage = javax.imageio.ImageIO.read(new ByteArrayInputStream(saved));
                    check(Arrays.equals(expected.toByteArray(), new SkinDataInputStream(new Image(savedImage.getRGB(0,0,64,64,null,0,64),64), template,
                            type.getChannel()).readNBytes(expected.size())), "Caller Save contains independently authored two-link carrier order/channel and siblings");
                    if (!mode.equals("publication-multi")) {
                        byte[] uploaded = com.naocraftlab.skins.runtime.CpmCallerHarness.explicitTestApply(library,
                                Files.readAllBytes(fixtures.resolve("classic-base.png")), SkinVariant.valueOf(variant.name()));
                        check(Arrays.equals(saved, uploaded), "Multi-publication actual Save/reopen/Apply retains exact carrier");
                    }
                    if (mode.equals("publication-multi")) {
                        Files.write(dir.resolve("expected.png"), saved);
                        Files.writeString(dir.resolve("expected.sha256"), hash);
                        Files.write(dir.resolve("expected.payload"), expected.toByteArray());
                    }
                }
            }
            try (var admitted = adapter.admit(new ByteArrayInputStream(model(mixed, new byte[0])))) {
                var prepared = adapter.prepare(admitted, admitted.automaticBase(variant).orElseThrow(), 27);
                check(prepared instanceof CpmModelFileConversion.Publication, "Lossless oversized siblings require sequential publication");
                try (var first = (CpmModelFileConversion.Publication) prepared) {
                    check(Arrays.equals(definition, first.wire()), "First exact raw definition wire");
                    var next = first.link("definition");
                    check(next instanceof CpmModelFileConversion.Publication, "Large siblings survive first link and need a second wire");
                    try (var second = (CpmModelFileConversion.Publication) next) {
                        check(Arrays.equals(siblings, second.wire()), "Second wire is exact contiguous raw siblings");
                        var finished = (CpmModelFileConversion.Inline) second.link("siblings");
                        var actual = new Image(finished.raster().argb(), 64);
                        check(Arrays.equals(bytes.toByteArray(), new SkinDataInputStream(actual, template, type.getChannel()).readNBytes(bytes.size())),
                                "Producer continuation preserves independent expected carrier bytes and order");
                    }
                }
            }
            Files.delete(source); Files.delete(base);
            repeatedLocalLinks(variant, fixtures, output);
            repeatedDefinitions(variant, fixtures, output);
            orderedSpans(variant, fixtures, output);
            System.out.println("PASS native topology " + variant + " automatic publication, ordered correct-kind wires, repeated local/raw definitions, exact Save and recovery");
        }
        forkApply(fixtures, output);
    }

    private static void forkApply(Path fixtures, Path output) throws Exception {
        Files.writeString(output.resolve("parent.pid"), Long.toString(ProcessHandle.current().pid()));
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), CpmModelFilePublicationProbe.class.getName(),
                fixtures.toString(), output.toString(), "fresh-apply"));
        Path log = output.resolve("fresh-apply.log");
        var child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!child.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new AssertionError("Fresh application JVM timed out");
        }
        System.out.print(Files.readString(log));
        check(child.exitValue() == 0, "Fresh application JVM exits successfully");
    }

    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of(args[0]), output = Path.of(args[1]); Files.createDirectories(output);
        CpmModelConversionProbe.main(new String[]{fixtures.toString(), output.toString(), "environment-only"});
        if (args[2].equals("fresh-apply")) { freshApply(fixtures, output); return; }
        if (args[2].equals("topology")) { topology(fixtures, output); return; }
        nestedLocal = args[2].equals("local-nested");
        boolean local = args[2].startsWith("local");
        boolean packageWire = args[2].contains("package");
        boolean small = args[2].contains("small");
        boolean legacy = args[2].equals("legacy");
        byte[] opaque = new byte[local || legacy ? 17 : 8192]; new Random(71).nextBytes(opaque);
        for (SkinModel variant : SkinModel.values()) {
            SkinType type = variant == SkinModel.SLIM ? SkinType.SLIM : SkinType.DEFAULT;
            byte[] definition = legacy ? backup(false, 8192) : null;
            byte[] data = payload(type, local, opaque, packageWire, definition), backup = local ? backup(packageWire, small ? 9 : 8192) : new byte[0];
            byte[] sourceBytes = model(data, backup);
            var adapter = new CpmModelFileAdapter();
            if (nestedLocal) {
                try (var ignored = adapter.admit(new ByteArrayInputStream(sourceBytes))) { throw new AssertionError("Nested local dependency must not be published as portable backup"); }
                catch (CpmModelFileConversion.Failure failure) { check(failure.reason() == CpmModelFileConversion.Reason.NONPORTABLE_OVERFLOW, "Nested missing data has typed portability outcome"); }
                continue;
            }
            Path dir = output.resolve(variant.name()); Files.createDirectories(dir);
            int[] expectedPixels;
            try (var admitted = adapter.admit(new ByteArrayInputStream(sourceBytes))) {
                var prepared = adapter.prepare(admitted, admitted.automaticBase(variant).orElseThrow(), 19);
                if (small) {
                    check(prepared instanceof CpmModelFileConversion.Inline, "Fitting native local backup must embed without consent/upload");
                    expectedPixels = ((CpmModelFileConversion.Inline) prepared).raster().argb();
                } else {
                    check(prepared instanceof CpmModelFileConversion.Publication, "Native local backup/overflow must request publication rather than reject or emit local cache links");
                    try (var publication = (CpmModelFileConversion.Publication) prepared) {
                        check(publication.revision() == 19 && publication.variant() == variant, "Exact owner revision and variant");
                        byte[] expectedWire = legacy ? definition : local ? backup : payload(type, false, opaque, packageWire, null, false);
                        check(Arrays.equals(expectedWire, publication.wire()), "Exact native wire preserves unknown bytes and UUID locks");
                        var carrier = ((CpmModelFileConversion.Inline) publication.link("synthetic")).raster();
                        expectedPixels = carrier.argb();
                        var image = new Image(carrier.argb(),64);
                        if (local || legacy) {
                            var raw = new SkinDataInputStream(image, com.tom.cpm.shared.MinecraftClientAccess.get().getDefinitionLoader().getTemplate(), type.getChannel());
                            check(raw.read() == 83, "Raw sibling carrier header");
                            var rawReader = new IOHelper(raw);
                            boolean unknown = false;
                            while (true) {
                                var tag = rawReader.readObjectBlock(Unknown.values(), (value, block) -> {
                                    if (value == Unknown.A63) check(Arrays.equals(opaque, block.getDin().readAllBytes()), "Exact unknown sibling retained beside new native link");
                                    if (value.ordinal() == ModelPartType.UUID_LOCK.ordinal()) check(block.readUUID().equals(UUID.fromString("00000000-0000-0000-0000-000000000123")), "Authored outer UUID lock preserved");
                                    return value;
                                });
                                if (tag == Unknown.A63) unknown = true;
                                if (tag.ordinal() == ModelPartType.END.ordinal()) break;
                            }
                            check(unknown, "Unknown sibling not dropped during publication continuation");
                        }
                        var stream = new SkinDataInputStream(image, com.tom.cpm.shared.MinecraftClientAccess.get().getDefinitionLoader().getTemplate(), type.getChannel());
                        check(stream.read() == 83, "Native carrier header");
                        var reader = new IOHelper(stream);
                        ModelPartLink link = null;
                        while (true) {
                            var part = reader.readObjectBlock(ModelPartType.VALUES,(kind,block)->kind.getFactory().create(block,null));
                            if (part instanceof ModelPartLink found) link = found;
                            if (part instanceof ModelPartEnd) break;
                        }
                        check(link != null && link.getLink().toString().equals("p:synthetic"), "Confirmed link uses native writer");
                        check(link.getType() == (legacy || local && !packageWire ? ModelPartType.DEFINITION_LINK : ModelPartType.PACKAGE_LINK), "Wire kind matches native reader");
                        check(readWire(link, publication.wire()) != null, "Actual native linked reader accepts published wire");
                        wrongKind(expectedWire, legacy || local && !packageWire);
                        Files.write(dir.resolve("expected.wire"), expectedWire);
                        Files.writeString(dir.resolve("expected.kind"), link.getType().name());
                        byte[] exposed = publication.wire(); exposed[0] ^= 1;
                        check(Arrays.equals(expectedWire, publication.wire()), "Publication bytes are defensively owned");
                        publication.close(); publication.close();
                        try { publication.link("synthetic"); throw new AssertionError("Closed publication reused"); }
                        catch (CpmModelFileConversion.Failure failure) { check(failure.reason() == CpmModelFileConversion.Reason.CLOSED, "Closed publication typed result"); }
                        try { publication.wire(); throw new AssertionError("Closed publication exposes bytes"); }
                        catch (IllegalStateException expected) { }
                    }
                }
            }
            Path source = Files.write(dir.resolve("source.cpmmodel"), sourceBytes);
            byte[] bundled = Files.readAllBytes(fixtures.resolve("classic-base.png"));
            Path base = Files.copy(fixtures.resolve(variant == SkinModel.SLIM ? "slim-base.png" : "classic-base.png"), dir.resolve("base.png"));
            String hash = com.naocraftlab.skins.runtime.CpmCallerHarness.saveThroughRuntime(adapter,bundled,source,
                    base,dir.resolve("library"),SkinVariant.valueOf(variant.name()),small ? "automatic" : "publication");
            var storage = new NclSkinsStorage(dir.resolve("library"), new PngValidator(), Clock.systemUTC());
            byte[] saved = storage.readAsset(hash);
            var savedImage = javax.imageio.ImageIO.read(new ByteArrayInputStream(saved));
            check(Arrays.equals(expectedPixels, savedImage.getRGB(0,0,64,64,null,0,64)), "Actual caller publishes and Saves the exact independently prepared carrier");
            check(Arrays.equals(sourceBytes, Files.readAllBytes(source)), "Caller never mutates source model");
            Files.write(dir.resolve("expected.png"), saved);
            Files.writeString(dir.resolve("expected.sha256"), hash);
            Files.write(dir.resolve("expected.payload"), new SkinDataInputStream(new Image(expectedPixels, 64),
                    com.tom.cpm.shared.MinecraftClientAccess.get().getDefinitionLoader().getTemplate(), type.getChannel()).readAllBytes());
            Files.delete(source);
            Files.delete(base);
            check(Arrays.equals(saved,new NclSkinsStorage(dir.resolve("library"),new PngValidator(),Clock.systemUTC()).readAsset(hash)), "Source-free exact saved carrier reopen");
        }
        if (!nestedLocal) forkApply(fixtures, output);
        System.out.println("PASS native ModelFile " + args[2] + (nestedLocal ? " missing nested dependency rejected before effects" : small ? " native inline preparation/caller/no-publication/Save/reopen" : " publication, native reader, explicit import, synthetic terminal, Save/reopen"));
    }
}
