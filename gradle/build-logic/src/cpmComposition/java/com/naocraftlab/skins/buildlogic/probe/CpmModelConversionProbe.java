package com.naocraftlab.skins.buildlogic.probe;

import com.naocraftlab.skins.client.CpmModelFileConversion;
import com.naocraftlab.skins.client.CpmModelFileConversion.*;
import com.naocraftlab.skins.compat.cpm.CpmModelFileAdapter;
import com.naocraftlab.skins.client.SkinModel;
import com.naocraftlab.skins.core.model.SkinVariant;
import com.naocraftlab.skins.core.model.PersonalSkinSource;
import com.naocraftlab.skins.core.png.PngValidator;
import com.naocraftlab.skins.core.png.CpmCarrierReader;
import com.naocraftlab.skins.core.service.LibraryService;
import com.naocraftlab.skins.core.storage.LibraryStorageAdapter;
import com.naocraftlab.skins.core.storage.NclSkinsStorage;
import com.tom.cpl.util.Image;
import com.tom.cpl.util.ImageIO.IImageIO;
import com.tom.cpl.math.Vec2i;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.MinecraftCommonAccess;
import com.tom.cpm.shared.MinecraftObjectHolder;
import com.tom.cpm.shared.definition.ModelDefinitionLoader;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.definition.Link;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.config.ResourceLoader.ResourceEncoding;
import com.tom.cpm.shared.editor.Exporter;
import com.tom.cpm.shared.io.ModelFile;
import com.tom.cpm.shared.io.SkinDataInputStream;
import com.tom.cpm.shared.model.SkinType;
import java.io.*;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public final class CpmModelConversionProbe {
    private static int forbidden, headers, assertions, upstreamLoads, upstreamConversions;
    private static boolean denyUpstreamLoad, failHeaderRead;
    private static volatile Runnable conversionGate = () -> {};
    public static void conversionGate(Runnable gate) { conversionGate = gate; }
    public static void upstreamConvert() { if (callerAdmission) com.naocraftlab.skins.runtime.CpmCallerHarness.requireWorkerConversion(); upstreamConversions++; conversionGate.run(); }
    private static int parserFault, parserClosures;
    private static InputStream parserInput;
    public static void upstreamLoad(InputStream input) throws IOException {
        if (callerAdmission) com.naocraftlab.skins.runtime.CpmCallerHarness.requireWorkerAdmission();
        upstreamLoads++;
        if (denyUpstreamLoad) throw new AssertionError("Unsafe upstream parser reached before admission");
        if (parserFault == 1) { input.close(); parserClosures++; }
        if (parserFault == 2) parserInput = input;
    }
    private static volatile Runnable clientRead = () -> {};
    private static boolean callerAdmission;
    private static GuardedLoader loader;
    private static final IImageIO IMAGE_IO = new IImageIO() {
        public Image read(File file) { throw forbidden(); }
        public Image read(InputStream input) throws IOException { return image(javax.imageio.ImageIO.read(input)); }
        public void write(Image image, File file) { throw forbidden(); }
        public void write(Image image, OutputStream output) throws IOException { javax.imageio.ImageIO.write(raster(image), "png", output); }
        public Vec2i getSize(InputStream input) throws IOException {
            headers++;
            if (failHeaderRead) throw new IOException("synthetic-private-header");
            var image = javax.imageio.ImageIO.read(input);
            if (parserInput != null) { parserInput.close(); parserClosures++; parserInput = null; }
            return new Vec2i(image.getWidth(), image.getHeight());
        }
    };
    private static AssertionError forbidden() { forbidden++;return new AssertionError("Forbidden model effect"); }
    private static class GuardedLoader extends ModelDefinitionLoader<Object> {
        GuardedLoader() { super(p->{throw forbidden();},p->{throw forbidden();},p->{throw forbidden();}); }
        @Override public InputStream load(Link l,ResourceEncoding e,ModelDefinition d) { throw forbidden(); }
        @Override public void putLocalResource(Link l,byte[] d) { throw forbidden(); }
        @Override public ModelDefinition loadModel(String s,Player<?> p) { throw forbidden(); }
        @Override public ModelDefinition loadModel(byte[] s,Player<?> p) { throw forbidden(); }
        @Override public ModelDefinition loadModel(Image s,Player<?> p) { throw forbidden(); }
    }
    @SuppressWarnings("unchecked")
    private static <T> T environment(Class<T> type) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->switch(method.getName()) {
            case "getImageIO" -> IMAGE_IO;
            case "getDefinitionLoader" -> { clientRead.run(); yield loader; }
            case "getVersionCheck" -> null;
            default -> throw forbidden();
        });
    }
    private static Image image(BufferedImage i) { return new Image(i.getRGB(0,0,i.getWidth(),i.getHeight(),null,0,i.getWidth()),i.getWidth()); }
    private static BufferedImage raster(Image i) { var r=new BufferedImage(i.getWidth(),i.getHeight(),BufferedImage.TYPE_INT_ARGB);r.setRGB(0,0,i.getWidth(),i.getHeight(),i.getData(),0,i.getWidth());return r; }
    private static void check(boolean condition,String message) { assertions++;if(!condition)throw new AssertionError(message); }
    private static void eq(byte[] a,byte[] b,String message) { check(Arrays.equals(a,b),message); }
    private static byte[] png(Image i) throws Exception { var out=new ByteArrayOutputStream();IMAGE_IO.write(i,out);return out.toByteArray(); }
    private static byte[] payload(Image i,SkinType type,int count) throws Exception { return new SkinDataInputStream(i,loader.getTemplate(),type.getChannel()).readNBytes(count); }
    private static Image rawExport(byte[] envelope,Image base,SkinType type) throws Exception {
        var result=new AtomicReference<Image>();
        Exporter.convert(ModelFile.load("fixture",new ByteArrayInputStream(envelope)),base,type,result::set,()->{throw new AssertionError("Fixture export failed");});
        return result.get();
    }
    private static LibraryService library(Path root) {
        var storage=new NclSkinsStorage(root,new PngValidator(),Clock.systemUTC());
        var adapter=new LibraryStorageAdapter(storage);return new LibraryService(adapter,adapter,Clock.systemUTC());
    }
    private static void positive(Path fixtures,Path output,String name,String route) throws Exception {
        positive(fixtures, output, name, route, new CpmModelFileAdapter());
    }
    private static void positive(Path fixtures, Path output, String name, String route, CpmModelFileConversion adapter) throws Exception {
        var variant=name.equals("slim")?SkinVariant.SLIM:SkinVariant.CLASSIC;
        var type=variant==SkinVariant.SLIM?SkinType.SLIM:SkinType.DEFAULT;
        byte[] model=Files.readAllBytes(fixtures.resolve(name+"-"+route+".cpmmodel"));
        byte[] expected=Files.readAllBytes(fixtures.resolve(name+"-"+route+".payload"));
        byte[] baseA=Files.readAllBytes(fixtures.resolve(name+"-base-a.cpmmodel"));
        byte[] expectedA=Files.readAllBytes(fixtures.resolve(name+"-base-a.payload"));
        var base=rawExport(baseA,image(javax.imageio.ImageIO.read(fixtures.resolve(name+"-base.png").toFile())),type);
        base.setRGB(8,8,0x00123456);
        int[] original=base.getData().clone(),template=loader.getTemplate().getData().clone();
        Path dir=output.resolve(name+"-"+route);Files.createDirectories(dir);
        Path source=dir.resolve("source.cpmmodel"),baseFile=dir.resolve("base.png");Files.write(source,model);Files.write(baseFile,png(base));
        var modelVariant=SkinModel.valueOf(variant.name());
        try(var admitted=adapter.admit(Files.newInputStream(source))) {
            check(admitted.requiredVariant().orElseThrow()==modelVariant,"Concrete required variant");
            Files.write(source,new byte[]{0});
            var input=new Raster(modelVariant,base.getData());var converted=((Inline)adapter.prepare(admitted,input,1)).raster();
            check(converted.variant()==modelVariant,"Converted variant preserves admitted concrete type");
            var convertedImage=new Image(converted.argb(),64);
            check(convertedImage.getRGB(8,8)==0x00123456,"Independent transparent RGB/alpha outside carrier is preserved");
            eq(expected,payload(convertedImage,type,expected.length),"Exact independent B payload and selected channel");
            check(!Arrays.equals(expectedA,payload(convertedImage,type,expectedA.length)),"B replaces base A");
            check(Arrays.equals(original,base.getData()),"Base unchanged");check(Arrays.equals(template,loader.getTemplate().getData()),"Template unchanged");
            int[] owned=converted.argb();owned[0]^=123;check(!Arrays.equals(owned,converted.argb()),"Result defensive ownership");
            byte[] normalized=new PngValidator().projectImport(png(convertedImage)).pngBytes();
            var normalizedImage=image(javax.imageio.ImageIO.read(new ByteArrayInputStream(normalized)));
            check(Arrays.equals(converted.argb(),normalizedImage.getData()),"All RGBA preserved through common normalization");
            eq(expected,payload(normalizedImage,type,expected.length),"Independent payload after normalization");
            eq(normalized, new PngValidator().projectImport(normalized).pngBytes(), "Final PNG container remains admitted independently of model classification");
            if(route.equals("embedded"))check(Arrays.stream(converted.argb()).anyMatch(p->(p>>>24)==0 && (p&0xffffff)!=0),"Significant transparent RGB");
            UUID account=UUID.fromString("00000000-0000-0000-0000-000000000001");Path data=dir.resolve("library");
            var saved=library(data).savePresetWithPersonalSkin(account,Optional.empty(),"Fixture","Fixture",variant,PersonalSkinSource.FILE,normalized,null);
            check(saved.state().presets().size()==1 && saved.state().personalSkins().size()==1,"One production Save pair");
            Files.delete(source);Files.delete(baseFile);
            check(library(data).load(account).presets().size()==1,"Source-free reopen");
            byte[] reopened=new NclSkinsStorage(data,new PngValidator(),Clock.systemUTC()).readAsset(saved.asset().sha256());
            eq(normalized,reopened,"Exact saved carrier bytes");
            eq(expected,payload(image(javax.imageio.ImageIO.read(new ByteArrayInputStream(reopened))),type,expected.length),"Source-free reopened B payload");
            check(saved.asset().variant()==variant,"Source-free variant");
            Files.write(dir.resolve("saved.png"),reopened);
        }
    }
    private static void callerPositive(Path fixtures, Path output, String name, String route) throws Exception {
        System.out.println("Composed gate " + name + "/" + route);
        SkinVariant variant = name.equals("slim") ? SkinVariant.SLIM : SkinVariant.CLASSIC;
        SkinType type = variant == SkinVariant.SLIM ? SkinType.SLIM : SkinType.DEFAULT;
        byte[] expected = Files.readAllBytes(fixtures.resolve(name + "-" + route + ".payload"));
        byte[] original = Files.readAllBytes(fixtures.resolve(name + "-" + route + ".cpmmodel"));
        Image base = rawExport(Files.readAllBytes(fixtures.resolve(name + "-base-a.cpmmodel")),
                image(javax.imageio.ImageIO.read(fixtures.resolve(name + "-base.png").toFile())), type);
        if (variant == SkinVariant.SLIM) base.setRGB(50, 16, base.getRGB(50, 16) & 0x00ffffff);
        byte[] expectedA = Files.readAllBytes(fixtures.resolve(name + "-base-a.payload"));
        eq(expectedA, payload(base, type, expectedA.length), "Independent base setup preserves actual A payload");
        base.setRGB(8, 8, 0x00123456);
        byte[] baseBytes = png(base);
        check(new PngValidator().projectImport(baseBytes).detectedVariant() == variant, "Same-type base setup uses ordinary detector, not required substitution");
        Path dir = output.resolve("caller-" + name + "-" + route);
        Files.createDirectories(dir);
        Path source = dir.resolve("source.cpmmodel"), baseFile = dir.resolve("base.png");
        Files.write(source, original); Files.write(baseFile, baseBytes);
        int before = upstreamConversions;
        clientRead = () -> {};
        callerAdmission = true;
        byte[] converted;
        String savedHash;
        try {
            savedHash = com.naocraftlab.skins.runtime.CpmCallerHarness.saveThroughRuntime(new CpmModelFileAdapter(),
                    Files.readAllBytes(fixtures.resolve("classic-base.png")), source, baseFile, dir.resolve("library"), variant, "same");
            converted = new NclSkinsStorage(dir.resolve("library"), new PngValidator(), Clock.systemUTC()).readAsset(savedHash);
        } finally { clientRead = () -> {}; callerAdmission = false; }
        check(upstreamConversions == before + 1, "Exactly one actual pinned exporter through production caller");
        Image result = image(javax.imageio.ImageIO.read(new ByteArrayInputStream(converted)));
        eq(expected, payload(result, type, expected.length), "Composed caller independent B payload/channel");
        check(result.getRGB(8,8) == 0x00123456, "Composed caller preserves transparent RGB and alpha");
        check(!Arrays.equals(Files.readAllBytes(fixtures.resolve(name + "-base-a.payload")),
                payload(result, type, Files.readAllBytes(fixtures.resolve(name + "-base-a.payload")).length)), "Composed caller replaces A with B");
        eq(original, Files.readAllBytes(source), "Composed model source unchanged");
        eq(baseBytes, Files.readAllBytes(baseFile), "Composed base source unchanged");
        var normalized = new PngValidator().projectImport(converted);
        eq(converted, normalized.pngBytes(), "Composed output already common normalized");
        UUID account = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var state = library(dir.resolve("library")).load(account);
        check(state.presets().size() == 1 && state.personalSkins().size() == 1, "Composed one inactive Save pair");
        check(state.skinAssets().stream().anyMatch(a -> a.sha256().equals(savedHash) && a.variant()==variant), "Composed Save exact variant");
        Files.delete(source); Files.delete(baseFile);
        Files.write(dir.resolve("expected.payload"), expected);
        Files.writeString(dir.resolve("expected.txt"), variant.name() + "\n" + savedHash + "\n"
                + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(converted)) + "\n");
        byte[] reopened = new NclSkinsStorage(dir.resolve("library"), new PngValidator(), Clock.systemUTC()).readAsset(savedHash);
        eq(converted, reopened, "Composed exact saved carrier after own source deletion");
    }
    private static void freshReopen(Path output) throws Exception {
        int count = 0;
        try (var dirs = Files.list(output)) {
            for (Path dir : dirs.filter(p -> p.getFileName().toString().startsWith("caller-")).toList()) {
                check(!Files.exists(dir.resolve("source.cpmmodel")) && !Files.exists(dir.resolve("base.png")), "Fresh process originals absent");
                List<String> expected = Files.readAllLines(dir.resolve("expected.txt"));
                SkinVariant variant = SkinVariant.valueOf(expected.get(0));
                var state = library(dir.resolve("library")).load(UUID.fromString("00000000-0000-0000-0000-000000000001"));
                check(state.presets().size() == 1 && state.personalSkins().size() == 1, "Fresh process actual storage Save state");
                check(state.skinAssets().stream().anyMatch(a -> a.sha256().equals(expected.get(1)) && a.variant() == variant), "Fresh process variant/asset identity");
                byte[] reopened = new NclSkinsStorage(dir.resolve("library"), new PngValidator(), Clock.systemUTC()).readAsset(expected.get(1));
                check(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(reopened)).equals(expected.get(2)), "Fresh process exact PNG hash");
                Image image = image(javax.imageio.ImageIO.read(new ByteArrayInputStream(reopened)));
                byte[] independent = Files.readAllBytes(dir.resolve("expected.payload"));
                eq(independent, payload(image, variant == SkinVariant.SLIM ? SkinType.SLIM : SkinType.DEFAULT, independent.length), "Fresh process independent payload/channel");
                check(image.getRGB(8,8) == 0x00123456, "Fresh process significant transparent RGB/alpha");
                eq(reopened, new PngValidator().projectImport(reopened).pngBytes(), "Fresh final PNG container admission without a second model classifier");
                byte[] uploaded=com.naocraftlab.skins.runtime.CpmCallerHarness.explicitTestApply(dir.resolve("library"),
                        Files.readAllBytes(output.resolve("bundled.png")), variant);
                eq(reopened, uploaded, "R09 captured Effects exact saved bytes");
                Image uploadImage=image(javax.imageio.ImageIO.read(new ByteArrayInputStream(uploaded)));
                eq(independent,payload(uploadImage,variant==SkinVariant.SLIM?SkinType.SLIM:SkinType.DEFAULT,independent.length),"R09 captured Effects independent B payload/channel");
                check(uploadImage.getRGB(8,8)==0x00123456,"R09 captured Effects transparent RGB/alpha");
                count++;
            }
        }
        check(count == 6, "Fresh process covers both channels and embedded/definition/package routes");
        System.out.println("PASS FRESH-PROCESS source-free composed Save/reopen routes=" + count);
    }

    private static void rejected(CpmModelFileAdapter adapter, byte[] model, Reason reason) throws Exception {
        try (var ignored = adapter.admit(new ByteArrayInputStream(model))) {
            throw new AssertionError("Expected typed rejection " + reason);
        } catch (Failure failure) { check(failure.reason() == reason, "Exact rejection " + reason + " observed " + failure.reason()); }
    }
    private static void types(Path fixtures) throws Exception {
        var adapter = new CpmModelFileAdapter();
        for (String id : List.of("unknown-type", "absent-type"))
            try (var admitted = adapter.admit(Files.newInputStream(fixtures.resolve(id + ".cpmmodel")))) {
                check(admitted.requiredVariant().isEmpty(), "Native unknown/absent does not invent a concrete lock");
            }
        rejected(adapter, Files.readAllBytes(fixtures.resolve("conflicting-type.cpmmodel")), Reason.INVALID_MODEL);
        for (String name : List.of("classic", "slim")) {
            SkinModel required = name.equals("slim") ? SkinModel.SLIM : SkinModel.CLASSIC;
            try (var admitted = adapter.admit(Files.newInputStream(fixtures.resolve(name + "-embedded.cpmmodel")))) {
                var wrong = new Raster(required == SkinModel.SLIM ? SkinModel.CLASSIC : SkinModel.SLIM, new int[4096]);
                int conversionsBefore=upstreamConversions;
                try { adapter.prepare(admitted, wrong, 1); throw new AssertionError("Mismatch exported"); }
                catch (Failure failure) { check(failure.reason() == Reason.VARIANT_UNSUPPORTED, "Mismatch rejected before exporter"); }
                check(upstreamConversions==conversionsBefore,"Mismatch stops at actual exporter entry");
            }
        }
    }
    private static byte[] vi(int n) {
        var b = new ByteArrayOutputStream();
        do { int part = n & 127; n >>>= 7; b.write(part | (n == 0 ? 0 : 128)); } while (n != 0);
        return b.toByteArray();
    }
    private static byte[] join(byte[]... parts) throws IOException {
        var b = new ByteArrayOutputStream();for (byte[] part : parts) b.write(part);return b.toByteArray();
    }
    private static byte[] checked(byte[] body) throws IOException {
        int sum = 0;for (byte b : body) sum = (sum + (b & 255)) & 65535;
        return join(new byte[]{83}, body, new byte[]{(byte)(sum >>> 8),(byte)sum});
    }

    private static byte[] envelope(byte[] name, byte[] desc, byte[] payload, byte[] overflow, String link, byte[] icon) throws IOException {
        byte[] l = link.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return checked(join(vi(name.length),name,vi(desc.length),desc,vi(payload.length),payload,vi(overflow.length),overflow,
                overflow.length==0?new byte[0]:join(new byte[]{(byte)l.length},l),vi(icon.length),icon));
    }

    private static void nativeFailures(Path fixtures) throws Exception {
        var adapter = new CpmModelFileAdapter();
        byte[] original = Files.readAllBytes(fixtures.resolve("classic-embedded.cpmmodel"));
        byte[] broken = original.clone(); broken[broken.length - 1] ^= 1;
        rejected(adapter, broken, Reason.INVALID_MODEL);
        rejected(adapter, Arrays.copyOf(original, original.length - 1), Reason.INVALID_MODEL);
    }
    private static void portability(Path fixtures, Path output) throws Exception {
        var adapter = new CpmModelFileAdapter();
        for (String name : List.of("classic", "slim")) {
            rejected(adapter, Files.readAllBytes(fixtures.resolve(name + "-local.cpmmodel")), Reason.INVALID_MODEL);
            rejected(adapter, Files.readAllBytes(fixtures.resolve(name + "-unknown-link.cpmmodel")), Reason.PORTABILITY_UNSUPPORTED);
            for (String route : List.of("public", "public-package", "mismatch-link")) positive(fixtures, output, name, route);
        }
    }
    private static byte[] iconPng(int width,int height) throws IOException {
        var out=new ByteArrayOutputStream();javax.imageio.ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB),"png",out);return out.toByteArray();
    }
    private static void icons(Path fixtures) throws Exception {
        var adapter = new CpmModelFileAdapter();
        byte[] payload = Files.readAllBytes(fixtures.resolve("classic-embedded.payload"));
        int before = headers;
        try (var model = adapter.admit(new ByteArrayInputStream(envelope(new byte[0], new byte[0], payload,
                new byte[0], "", iconPng(256, 256))))) {
            check(headers == before + 1, "Native CPM processes its internal icon");
            check(adapter.prepare(model, new Raster(SkinModel.CLASSIC, new int[4096]), 1) instanceof Inline, "Native icon model produces inline carrier");
            check(headers == before + 1, "Conversion does not reread the source icon");
        }
        rejected(adapter, envelope(new byte[0], new byte[0], payload, new byte[0], "", iconPng(257, 256)), Reason.INVALID_MODEL);
    }
    private static void ownership(Path fixtures,Path output) throws Exception {
        var adapter=new CpmModelFileAdapter();byte[] bytes=Files.readAllBytes(fixtures.resolve("classic-embedded.cpmmodel"));
        int[] closed={0};var stream=new ByteArrayInputStream(bytes){@Override public void close(){closed[0]++;}};
        var model=adapter.admit(stream);check(closed[0]==1,"Input closed once after admission");
        check(adapter.availability()==Availability.READY,"Initialized optional exporter available");
        model.close();model.close();
        try{adapter.prepare(model,new Raster(SkinModel.CLASSIC,new int[4096]),1);throw new AssertionError("Closed model exported");}
        catch(Failure failure){check(failure.reason()==Reason.CLOSED,"Idempotent closed handle refuses export");}
        try(var valid=adapter.admit(new ByteArrayInputStream(bytes))) {
            try{new CpmModelFileAdapter().prepare(valid,new Raster(SkinModel.CLASSIC,new int[4096]),1);throw new AssertionError("Foreign handle exported");}
            catch(Failure failure){check(failure.reason()==Reason.CLOSED,"Exact adapter handle ownership");}
        }
        Path first=output.resolve("first.cpmmodel"),second=output.resolve("second.cpmmodel"),link=output.resolve("selected.cpmmodel");
        Files.write(first,bytes);Files.write(second,new byte[]{0});Files.createSymbolicLink(link,first.toAbsolutePath());
        try(InputStream original=Files.newInputStream(link)) {
            Files.delete(link);Files.createSymbolicLink(link,second.toAbsolutePath());
            try(var valid=adapter.admit(original)){check(valid.requiredVariant().orElseThrow()==SkinModel.CLASSIC,"Opened source inode survives symlink replacement");}
        }

        MinecraftObjectHolder.setClientObject(null);check(adapter.availability()==Availability.UNAVAILABLE,"Uninitialized safe unavailable");
        try(var valid=adapter.admit(new ByteArrayInputStream(bytes))) {
            try{adapter.prepare(valid,new Raster(SkinModel.CLASSIC,new int[4096]),1);throw new AssertionError("Uninitialized export");}
            catch(Failure failure){check(failure.reason()==Reason.CPM_UNAVAILABLE,"Uninitialized typed error");}
        } finally {MinecraftObjectHolder.setClientObject(environment(MinecraftClientAccess.class));}
        try(var files=Files.list(Path.of(System.getProperty("java.io.tmpdir")))){check(files.findAny().isEmpty(),"All own temporaries retired deterministically");}
    }
    private static final class AdmissionSource extends InputStream {
        private final byte[] bytes;
        private final int failAfter;
        private final boolean failClose;
        private int position, closes;
        private AdmissionSource(byte[] bytes, int failAfter, boolean failClose) {
            this.bytes = bytes; this.failAfter = failAfter; this.failClose = failClose;
        }
        @Override public int read() throws IOException {
            if (failAfter >= 0 && position >= failAfter) throw new IOException("synthetic-private-source");
            return position == bytes.length ? -1 : bytes[position++] & 255;
        }
        @Override public int read(byte[] output, int offset, int count) throws IOException {
            if (count == 0) return 0;
            if (failAfter >= 0 && position >= failAfter) throw new IOException("synthetic-private-source");
            if (position == bytes.length) return -1;
            int length = Math.min(count, bytes.length - position);
            if (failAfter >= 0) length = Math.min(length, failAfter - position);
            System.arraycopy(bytes, position, output, offset, length); position += length;
            return length;
        }
        @Override public void close() throws IOException {
            closes++;
            if (failClose) throw new IOException("synthetic-private-close");
        }
    }
    private static void safeFailure(Failure failure, String expected) {
        check(failure.reason().name().equals(expected), "Admission category expected " + expected + " observed " + failure.reason());
        check(failure.getMessage().equals(expected), "Only fixed failure category message");
        check(failure.getCause() == null, "No raw admission cause");
        check(failure.getSuppressed().length == 0, "No raw admission suppressed exceptions");
    }
    private static void temporaryFilesRetired() throws IOException {
        try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            check(files.findAny().isEmpty(), "Admission temporaries retired on success and failure");
        }
    }
    private static void admissionFailure(AdmissionSource source, String expected) throws Exception {
        try (var ignored = new CpmModelFileAdapter().admit(source)) {
            throw new AssertionError("Expected admission category " + expected);
        } catch (Failure failure) { safeFailure(failure, expected); }
        check(source.closes == 1, "Failed source closed exactly once");
        temporaryFilesRetired();
    }
    private static void admissionPositives(Path fixtures) throws Exception {
        for (String name : List.of("classic", "slim")) {
            var source = new AdmissionSource(Files.readAllBytes(fixtures.resolve(name + "-embedded.cpmmodel")), -1, false);
            try (var admitted = new CpmModelFileAdapter().admit(source)) {
                check(admitted.requiredVariant().orElseThrow() == (name.equals("classic") ? SkinModel.CLASSIC : SkinModel.SLIM), "Admission positive concrete variant");
                check(source.position == source.bytes.length && source.closes == 1, "Valid source fully read and closed exactly once");
            }
            temporaryFilesRetired();
        }
        byte[] valid = Files.readAllBytes(fixtures.resolve("classic-embedded.cpmmodel"));
        byte[] signature = valid.clone(); signature[0] = 0;
        byte[] checksum = valid.clone(); checksum[checksum.length - 1] ^= 1;
        for (byte[] bad : List.of(signature, checksum, Arrays.copyOf(valid, valid.length - 1)))
            admissionFailure(new AdmissionSource(bad, -1, false), "INVALID_MODEL");
        int loadsBefore = upstreamLoads, headersBefore = headers;
        byte[] withIcon = envelope(new byte[0], new byte[0], Files.readAllBytes(fixtures.resolve("classic-embedded.payload")), new byte[0], "", iconPng(1,1));
        failHeaderRead = true;
        try { admissionFailure(new AdmissionSource(withIcon, -1, false), "INVALID_MODEL"); }
        finally { failHeaderRead = false; }
        check(upstreamLoads == loadsBefore + 1 && headers == headersBefore + 1, "Parser format I/O reaches real upstream icon reader after envelope admission");
        System.out.println("PASS valid Classic/Slim, malformed signature/checksum/truncation and actual parser format controls");
    }
    private static void parserIo(Path fixtures) throws Exception {
        admissionPositives(fixtures);
        for (String name : List.of("classic", "slim")) for (int fault : List.of(1, 2)) for (boolean failClose : List.of(false, true)) {
            byte[] bytes = Files.readAllBytes(fixtures.resolve(name + "-embedded.cpmmodel"));
            if (fault == 2) bytes = envelope(new byte[0], new byte[0], Files.readAllBytes(fixtures.resolve(name + "-embedded.payload")), new byte[0], "", iconPng(1,1));
            var source = new AdmissionSource(bytes, -1, failClose);
            int loadsBefore = upstreamLoads, closuresBefore = parserClosures, headersBefore = headers;
            Failure observed = null;
            parserFault = fault;
            try (var ignored = new CpmModelFileAdapter().admit(source)) {
                throw new AssertionError("Closed staged reader admitted");
            } catch (Failure failure) { observed = failure; }
            finally { parserFault = 0; parserInput = null; }
            check(source.position == bytes.length && source.closes == 1, "Reader fault occurs after capture with exactly one source close");
            check(upstreamLoads == loadsBefore + 1 && parserClosures == closuresBefore + 1, "Reader fault reaches actual pinned parser after envelope admission");
            check(headers == headersBefore + (fault == 2 ? 1 : 0), "Early versus post-icon parser read boundary");
            temporaryFilesRetired();
            safeFailure(observed, "IO_FAILED");
        }
        System.out.println("PASS actual staged-channel reader I/O after envelope admission, early/post-icon, Classic/Slim and suppressed close");
    }
    private static void sourceIo(Path fixtures) throws Exception {
        admissionPositives(fixtures);
        byte[] valid = Files.readAllBytes(fixtures.resolve("classic-embedded.cpmmodel"));
        var descriptors = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        long before = descriptors instanceof com.sun.management.UnixOperatingSystemMXBean unix ? unix.getOpenFileDescriptorCount() : -1;
        for (int prefix : List.of(0, 8)) for (boolean closeFailure : List.of(false, true)) {
            var source = new AdmissionSource(valid, prefix, closeFailure);
            int loadsBefore = upstreamLoads;
            admissionFailure(source, "IO_FAILED");
            check(source.position == prefix, "I/O reached exact valid source prefix");
            check(upstreamLoads == loadsBefore, "Source I/O rejected before upstream parser");
        }
        admissionFailure(new AdmissionSource(valid, -1, true), "IO_FAILED");
        if (descriptors instanceof com.sun.management.UnixOperatingSystemMXBean unix)
            check(unix.getOpenFileDescriptorCount() == before, "No leaked staging channel descriptors after partial read or close failures");
    }
    private static void typedClose(Path fixtures) throws Exception {
        admissionPositives(fixtures);
        byte[] valid = Files.readAllBytes(fixtures.resolve("classic-embedded.cpmmodel"));
        byte[] checksum = valid.clone(); checksum[checksum.length - 1] ^= 1;
        admissionFailure(new AdmissionSource(checksum, -1, true), "INVALID_MODEL");
        byte[] p = Files.readAllBytes(fixtures.resolve("classic-embedded.payload"));
        admissionFailure(new AdmissionSource(envelope(new byte[4097], new byte[0], p, new byte[0], "", new byte[0]), -1, true), "IO_FAILED");
        for (var pair : Map.of("unknown-type", "IO_FAILED", "classic-local", "INVALID_MODEL", "classic-unknown-link", "PORTABILITY_UNSUPPORTED").entrySet())
            admissionFailure(new AdmissionSource(Files.readAllBytes(fixtures.resolve(pair.getKey() + ".cpmmodel")), -1, true), pair.getValue());
    }
    private static void stagingIo(Path fixtures) throws Exception {
        var source = new AdmissionSource(Files.readAllBytes(fixtures.resolve("classic-embedded.cpmmodel")), -1, true);
        int loadsBefore = upstreamLoads;
        try (var ignored = new CpmModelFileAdapter().admit(source)) { throw new AssertionError("Unavailable staging location admitted"); }
        catch (Failure failure) { safeFailure(failure, "IO_FAILED"); }
        check(source.closes == 1 && source.position == 0 && upstreamLoads == loadsBefore, "Staging creation failure closes unread source without parser");
    }
    private static void closeRace(Path fixtures) throws Exception {
        var adapter=new CpmModelFileAdapter();var model=adapter.admit(Files.newInputStream(fixtures.resolve("classic-embedded.cpmmodel")));
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var closeDone=new java.util.concurrent.CountDownLatch(1);
        var failure=new AtomicReference<Throwable>();
        clientRead=()->{entered.countDown();try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Bounded client gate");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}};
        Thread conversion=new Thread(()->{try{check(adapter.prepare(model,new Raster(SkinModel.CLASSIC,new int[4096]),1) instanceof Inline,"Open race handle produces inline carrier");}catch(Throwable problem){failure.set(problem);}},"owned-cpm-convert-test");
        Thread close=new Thread(()->{model.close();closeDone.countDown();},"owned-cpm-close-test");
        try {
            conversion.start();check(entered.await(5,java.util.concurrent.TimeUnit.SECONDS),"Actual client convert held after handle admission");
            close.start();check(!closeDone.await(50,java.util.concurrent.TimeUnit.MILLISECONDS),"Close does not release model during active synchronous consumer");
        } finally {release.countDown();conversion.join(5000);close.join(5000);clientRead=()->{};model.close();}
        check(!conversion.isAlive()&&!close.isAlive()&&failure.get()==null,"Bounded conversion/close both terminate");
        try{adapter.prepare(model,new Raster(SkinModel.CLASSIC,new int[4096]),1);throw new AssertionError("Post-close conversion");}
        catch(Failure expected){check(expected.reason()==Reason.CLOSED,"Closed race handle cannot reopen");}
        var reentrant=adapter.admit(Files.newInputStream(fixtures.resolve("classic-embedded.cpmmodel")));
        clientRead=reentrant::close;
        try{adapter.prepare(reentrant,new Raster(SkinModel.CLASSIC,new int[4096]),1);throw new AssertionError("Reentrant close published");}
        catch(Failure expected){check(expected.reason()==Reason.CLOSED || expected.reason()==Reason.CONVERSION_FAILED,"Reentrant retirement never publishes");}
        finally{clientRead=()->{};reentrant.close();}
    }
    public static int[] damagePixels(int[] pixels) {
        int[] copy=pixels.clone();for(int i=0;i<copy.length;i++)if((copy[i]>>>24)==0 && (copy[i]&0xffffff)!=0){copy[i]^=1;break;}return copy;
    }
    public static SkinModel redetect(int[] pixels) {
        try{return SkinModel.valueOf(new PngValidator().projectImport(png(new Image(pixels,64))).detectedVariant().name());}
        catch(Exception problem){throw new AssertionError(problem);}
    }
    private static void exporterFailure(Path fixtures) throws Exception {
        var adapter=new CpmModelFileAdapter();
        try(var model=adapter.admit(Files.newInputStream(fixtures.resolve("classic-embedded.cpmmodel")))) {
            try {adapter.prepare(model,new Raster(SkinModel.CLASSIC,new int[4096]),1);throw new AssertionError("Invalid exporter terminal published");}
            catch(Failure failure){check(failure.reason()==Reason.CONVERSION_FAILED,"Single safe typed exporter failure");check(failure.getCause()==null,"No upstream exception retention");}
        }
    }
    private static void composition(Path fixtures, Path output) throws Exception {
        var environment = CpmOptionalEnvironmentProbe.environment();
        var adapter = environment.modelFileConversion().orElseThrow(() -> new AssertionError("Installed initialized optional composition must expose converter"));
        check(environment.modelFileConversion().orElseThrow() == adapter, "Stable accessor owner identity");
        try (var admitted = adapter.admit(Files.newInputStream(fixtures.resolve("classic-embedded.cpmmodel")))) {
            check(environment.modelFileConversion().orElseThrow().prepare(admitted, new Raster(SkinModel.CLASSIC, new int[4096]), 1) instanceof Inline, "Initialized optional composition produces inline carrier");
        }
        positive(fixtures, output, "classic", "embedded", adapter);
        positive(fixtures, output, "slim", "embedded", adapter);
        MinecraftObjectHolder.setClientObject(null);
        check(environment.modelFileConversion().isEmpty(), "Uninitialized installed integration is unavailable");
        MinecraftObjectHolder.setClientObject(environment(MinecraftClientAccess.class));
        var initialized = loader; loader = null;
        check(environment.modelFileConversion().isEmpty(), "Missing definition loader unavailable");
        loader = new GuardedLoader() { @Override public Image getTemplate() { return null; } };
        check(environment.modelFileConversion().isEmpty(), "Missing initialized template unavailable");
        loader = initialized;
        check(environment.modelFileConversion().orElseThrow() == adapter, "Reinitialized accessor retains owner");
        Player.setEnableRendering(false);
        try {
            check(compatState(environment) == com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.State.INACTIVE, "Model-loading setting remains inactive");
            check(environment.modelFileConversion().orElseThrow() == adapter, "Export availability does not override model-loading setting");
        } finally { Player.setEnableRendering(true); }
        clientRead = () -> { throw new NoSuchMethodError(); };
        try { check(environment.modelFileConversion().isEmpty(), "Unsupported installed ABI safely unavailable"); }
        finally { clientRead = () -> {}; }
    }
    private static com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.State compatState(com.naocraftlab.skins.client.SkinExtensionEnvironmentSource environment) {
        return environment.snapshot().consumers().get(com.naocraftlab.skins.client.SkinExtensionEnvironmentSource.Consumer.CUSTOMIZABLE_PLAYER_MODELS);
    }
    public static void main(String[] args) throws Exception {
        Path fixtures=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);
        if (args.length > 2 && args[2].equals("staging-io")) {
            stagingIo(fixtures);
            check(forbidden == 0, "Zero staging parser or dependency effects");
            System.out.println("PASS staging I/O category, source close and sanitized failure assertions=" + assertions);
            return;
        }
        MinecraftObjectHolder.setCommonObject(environment(MinecraftCommonAccess.class));
        MinecraftObjectHolder.setClientObject(environment(MinecraftClientAccess.class));loader=new GuardedLoader();
        if (args.length > 2 && args[2].equals("environment-only")) return;
        if (args.length > 2 && args[2].equals("frozen-input")) {
            var adapter = new CpmModelFileAdapter();
            javax.imageio.ImageIO.setUseCache(false);
            Path temporary = Path.of(System.getProperty("java.io.tmpdir"));
            Path held = temporary.resolveSibling(temporary.getFileName() + "-held");
            try (var frozen = com.naocraftlab.skins.client.FrozenImportInput.capture(fixtures.resolve("classic-embedded.cpmmodel"));
                 var input = frozen.open()) {
                try (var positive = adapter.admit(frozen.open())) {
                    check(positive.requiredVariant().orElseThrow() == SkinModel.CLASSIC, "Healthy actual native parser control");
                }
                Files.move(temporary, held);
                try (var model = adapter.admit(input)) {
                    check(model.requiredVariant().orElseThrow() == SkinModel.CLASSIC, "Frozen reader reaches real native parser without second staging");
                } finally { Files.move(held, temporary); }
            }
            System.out.println("PASS same frozen native admission without second staging");
            return;
        }
        if (args.length > 2 && args[2].equals("remote-failure")) {
            byte[] bundled=Files.readAllBytes(fixtures.resolve("classic-base.png"));
            for(boolean player:List.of(false,true)) new com.naocraftlab.skins.runtime.CpmCallerHarness(new CpmModelFileAdapter(),bundled).remoteFailure(fixtures.resolve("classic-embedded.cpmmodel"),fixtures.resolve("classic-base.png"),player);
        }
        else if (args.length > 2 && args[2].equals("external-stale")) {
            byte[] bundled=Files.readAllBytes(fixtures.resolve("classic-base.png"));
            for(String route:List.of("source","folder","commit"))
                new com.naocraftlab.skins.runtime.CpmCallerHarness(new CpmModelFileAdapter(),bundled).staleExternalPreparation(fixtures.resolve("classic-embedded.cpmmodel"),route);
        }
        else if (args.length > 2 && (args[2].equals("lock") || args[2].equals("external"))) {
            byte[] bundled=Files.readAllBytes(fixtures.resolve("classic-base.png"));
            new com.naocraftlab.skins.runtime.CpmCallerHarness(new CpmModelFileAdapter(),bundled).ordinary(fixtures.resolve("classic-base.png"));
            System.out.println("PASS ordinary setup before scoped policy assertions");
            for(String name:List.of("classic","slim")) {
                var harness=new com.naocraftlab.skins.runtime.CpmCallerHarness(new CpmModelFileAdapter(),bundled);
                if(args[2].equals("lock"))harness.lockRefresh(fixtures.resolve(name+"-embedded.cpmmodel"),SkinVariant.valueOf(name.toUpperCase(java.util.Locale.ROOT)));
                else harness.blockedExternal(fixtures.resolve(name+"-embedded.cpmmodel"));
            }
        }
        else if (args.length > 2 && args[2].equals("catalog-purpose")) {
            byte[] bundled=Files.readAllBytes(fixtures.resolve("classic-base.png"));
            new com.naocraftlab.skins.runtime.CpmCallerHarness(new CpmModelFileAdapter(),bundled).ordinary(fixtures.resolve("classic-base.png"));
            for(String name:List.of("classic","slim")) positive(fixtures,output,name,"embedded");
            System.out.println("PASS valid ordinary and exporter setup before catalog assertion");
            for(String name:List.of("classic","slim")) for(boolean personal:List.of(false,true)) for(boolean missing:List.of(false,true)) for(boolean filtered:List.of(false,true)) {
                SkinVariant variant=SkinVariant.valueOf(name.toUpperCase(java.util.Locale.ROOT));
                int before=upstreamConversions;
                byte[] converted=new com.naocraftlab.skins.runtime.CpmCallerHarness(new CpmModelFileAdapter(),bundled).catalog(fixtures.resolve(name+"-embedded.cpmmodel"),fixtures.resolve("classic-base.png"),variant,personal,missing,filtered);
                check(upstreamConversions==before+(missing?0:1),"Catalog actual exporter count");
                if(!missing) {
                    byte[] expected=Files.readAllBytes(fixtures.resolve(name+"-embedded.payload"));
                    eq(expected,payload(image(javax.imageio.ImageIO.read(new ByteArrayInputStream(converted))),name.equals("classic")?SkinType.DEFAULT:SkinType.SLIM,expected.length),"Catalog exact payload over identical Classic pixels but selected tag");
                }
            }
        }
        else if (args.length > 2 && args[2].equals("purpose")) {
            byte[] bundled = Files.readAllBytes(fixtures.resolve("classic-base.png"));
            new com.naocraftlab.skins.runtime.CpmCallerHarness(new CpmModelFileAdapter(), bundled).ordinary(fixtures.resolve("classic-base.png"));
            for (String name : List.of("classic", "slim")) positive(fixtures, output, name, "embedded");
            System.out.println("PASS independent ordinary and actual pinned positive setup before purpose RED");
            for (String name : List.of("classic", "slim")) for (boolean player : List.of(false, true)) {
                int before = upstreamConversions;
                var retry = new com.naocraftlab.skins.runtime.CpmCallerHarness(new CpmModelFileAdapter(), bundled);
                retry.remote(fixtures.resolve(name + "-embedded.cpmmodel"), fixtures.resolve((name.equals("classic") ? "slim" : "classic") + "-base.png"), player, true);
                check(upstreamConversions == before, "Remote mismatch zero actual exporter calls");
                retry.retryRemote(fixtures.resolve(name+"-base.png"),player);
                check(upstreamConversions==++before,"Same-context same-type retry one actual export");
                byte[] converted = new com.naocraftlab.skins.runtime.CpmCallerHarness(new CpmModelFileAdapter(), bundled).remote(
                        fixtures.resolve(name + "-embedded.cpmmodel"), fixtures.resolve(name + "-base.png"), player, false);
                SkinType type = name.equals("classic") ? SkinType.DEFAULT : SkinType.SLIM;
                byte[] expected = Files.readAllBytes(fixtures.resolve(name + "-embedded.payload"));
                eq(expected, payload(image(javax.imageio.ImageIO.read(new ByteArrayInputStream(converted))), type, expected.length), "Remote production consumer actual exporter independent payload");
                check(upstreamConversions == before + 1, "Remote same-type one actual export");
            }
        }
        else if (args.length > 2 && args[2].equals("caller-cancel")) new com.naocraftlab.skins.runtime.CpmCallerHarness(new CpmModelFileAdapter(), Files.readAllBytes(fixtures.resolve("classic-base.png"))).cancel(fixtures.resolve("classic-embedded.cpmmodel"), fixtures.resolve("classic-base.png"));
        else if (args.length > 2 && args[2].equals("caller-failure")) new com.naocraftlab.skins.runtime.CpmCallerHarness(new CpmModelFileAdapter(), Files.readAllBytes(fixtures.resolve("classic-base.png"))).terminal(fixtures.resolve("classic-embedded.cpmmodel"), fixtures.resolve("classic-base.png"));
        else if (args.length > 2 && args[2].equals("picker")) com.naocraftlab.skins.runtime.CpmCallerHarness.pickerSetup(fixtures.resolve("classic-embedded.cpmmodel"), fixtures.resolve("classic-base.png"));
        else if (args.length > 2 && args[2].equals("save-scopes")) {
            for(String name:List.of("classic","slim")) for(String mode:List.of("same","retry","swap","callback","reuse")) {
                SkinVariant variant=SkinVariant.valueOf(name.toUpperCase(Locale.ROOT));
                SkinType type=variant==SkinVariant.SLIM?SkinType.SLIM:SkinType.DEFAULT;
                Path dir=output.resolve(name+"-"+mode);Files.createDirectories(dir);
                Path source=dir.resolve("source.cpmmodel"),baseFile=dir.resolve("base.png");
                Files.copy(fixtures.resolve(name+"-embedded.cpmmodel"),source);
                Image base=rawExport(Files.readAllBytes(fixtures.resolve(name+"-base-a.cpmmodel")),image(javax.imageio.ImageIO.read(fixtures.resolve(name+"-base.png").toFile())),type);
                if(variant==SkinVariant.SLIM)base.setRGB(50,16,base.getRGB(50,16)&0x00ffffff);
                Files.write(baseFile,png(base));
                callerAdmission=true;clientRead=()->{};
                try {
                    byte[] bundled=Files.readAllBytes(fixtures.resolve("classic-base.png"));
                    String actualMode=mode.equals("reuse")?"same":mode;
                    com.naocraftlab.skins.runtime.CpmCallerHarness.saveThroughRuntime(new CpmModelFileAdapter(),bundled,source,baseFile,dir.resolve("library"),variant,actualMode);
                    if(mode.equals("reuse"))com.naocraftlab.skins.runtime.CpmCallerHarness.saveThroughRuntime(new CpmModelFileAdapter(),bundled,source,baseFile,dir.resolve("library"),variant,"same");
                } finally {callerAdmission=false;clientRead=()->{};}
                System.out.println("PASS R17 " + name + " " + mode);
            }
        }
        else if (args.length > 2 && args[2].equals("fresh-reopen")) freshReopen(output);
        else if (args.length > 2 && (args[2].equals("caller") || args[2].equals("caller-setup") || args[2].equals("caller-gates"))) {
            byte[] bundled = Files.readAllBytes(fixtures.resolve("classic-base.png"));
            new com.naocraftlab.skins.runtime.CpmCallerHarness(new CpmModelFileAdapter(), bundled).ordinary(fixtures.resolve("classic-base.png"));
            for (String name : List.of("classic", "slim")) positive(fixtures, output, name, "embedded");
            System.out.println("PASS valid actual pins/fixtures and ordinary setup before caller assertion");
            if (args[2].equals("caller")) new com.naocraftlab.skins.runtime.CpmCallerHarness(new CpmModelFileAdapter(), bundled).convert(
                    fixtures.resolve("classic-embedded.cpmmodel"), fixtures.resolve("classic-base.png"), SkinVariant.CLASSIC);
            if (args[2].equals("caller-gates")) {
                Files.write(output.resolve("bundled.png"),bundled);
                new com.naocraftlab.skins.runtime.CpmCallerHarness(new CpmModelFileAdapter(), bundled).cancel(fixtures.resolve("classic-embedded.cpmmodel"), fixtures.resolve("classic-base.png"));
                for (int stage = 0; stage <= 2; stage++) {
                    int conversionsBefore = upstreamConversions;
                    new com.naocraftlab.skins.runtime.CpmCallerHarness(new CpmModelFileAdapter(), bundled).stale(fixtures.resolve("classic-embedded.cpmmodel"), fixtures.resolve("classic-base.png"), stage);
                    check(upstreamConversions == conversionsBefore + (stage >= 2 ? 1 : 0), "Stale scheduling guard reaches actual converter only before retirement");
                }
                for (String name : List.of("classic", "slim")) {
                    int conversionsBefore = upstreamConversions;
                    new com.naocraftlab.skins.runtime.CpmCallerHarness(new CpmModelFileAdapter(), bundled).mismatch(fixtures.resolve(name + "-embedded.cpmmodel"), fixtures.resolve((name.equals("slim") ? "classic" : "slim") + "-base.png"));
                    check(upstreamConversions == conversionsBefore, "Actual caller mismatch exporter calls zero");
                }
                for (String name : List.of("classic", "slim")) for (String route : List.of("embedded", "public", "public-package"))
                    callerPositive(fixtures, output, name, route);
                types(fixtures); portability(fixtures, output); closeRace(fixtures);
                for(String mode:List.of("purpose","catalog-purpose","lock","external","external-stale","remote-failure"))
                    main(new String[]{fixtures.toString(),output.resolve("base-"+mode).toString(),mode});
            }
        } else if (args.length > 2 && args[2].equals("all")) {
            parserIo(fixtures);sourceIo(fixtures);typedClose(fixtures);
            types(fixtures);nativeFailures(fixtures);icons(fixtures);portability(fixtures,output);ownership(fixtures,output);closeRace(fixtures);
            for(String name:List.of("classic","slim"))positive(fixtures,output,name,"embedded");
            check(upstreamLoads>0,"Actual parser entry sentinel exercised by positive controls");
        } else if (args.length > 2 && args[2].equals("composition")) composition(fixtures, output);
        else if (args.length > 2 && args[2].equals("portable-positive")) {
            for(String name:List.of("classic","slim"))for(String route:List.of("public","public-package"))positive(fixtures,output,name,route);
        } else if (args.length > 2 && args[2].equals("exporter-failure")) exporterFailure(fixtures);
        else if (args.length > 2 && args[2].equals("admission-positive")) admissionPositives(fixtures);
        else if (args.length > 2 && args[2].equals("parser-io")) parserIo(fixtures);
        else if (args.length > 2 && args[2].equals("source-io")) sourceIo(fixtures);
        else if (args.length > 2 && args[2].equals("typed-close")) typedClose(fixtures);
        else if (args.length > 2 && args[2].equals("ownership")) ownership(fixtures,output);
        else if (args.length > 2 && args[2].equals("icons")) icons(fixtures);
        else if (args.length > 2 && args[2].equals("portability")) portability(fixtures,output);
        else if (args.length > 2 && args[2].equals("native-failures")) nativeFailures(fixtures);
        else if (args.length > 2 && args[2].equals("types")) types(fixtures);
        else for(String name:List.of("classic","slim"))positive(fixtures,output,name,"embedded");
        check(forbidden==0,"Zero fetch/resolve/cache/upload effects");
        System.out.println("PASS actual adapter/exporter/template/reader/common-normalize/Save/reopen assertions="+assertions+" forbidden="+forbidden);
    }
}
