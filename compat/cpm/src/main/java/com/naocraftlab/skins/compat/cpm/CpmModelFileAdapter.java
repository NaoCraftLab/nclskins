package com.naocraftlab.skins.compat.cpm;

import com.naocraftlab.skins.client.CpmModelFileConversion;
import com.naocraftlab.skins.client.SkinModel;
import com.tom.cpl.util.Image;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.editor.Exporter;
import com.tom.cpm.shared.io.ModelFile;
import com.tom.cpm.shared.model.SkinType;
import java.io.*;

import java.nio.file.*;
import java.util.Objects;

public final class CpmModelFileAdapter implements CpmModelFileConversion.DependencyAdmission {
    @Override public Availability availability() {
        try {
            var client = MinecraftClientAccess.get();
            return client != null && client.getDefinitionLoader() != null
                    && client.getDefinitionLoader().getTemplate() != null ? Availability.READY : Availability.UNAVAILABLE;
        } catch (RuntimeException | LinkageError failure) { return Availability.UNAVAILABLE; }
    }

    @Override public AdmittedModel admitFile(Path source) throws Failure {
        InputStream input;
        try { input = Files.newInputStream(Objects.requireNonNull(source)); }
        catch (IOException failure) { throw new Failure(Reason.IO_FAILED); }
        return admit(input);
    }

    @Override public AdmittedModel admit(InputStream source) throws Failure {
        Admission result = admit(source, java.util.Map.of());
        if (result instanceof Ready ready) return ready.model();
        throw new Failure(Reason.PORTABILITY_UNSUPPORTED);
    }

    @Override public Admission admit(InputStream source, java.util.Map<String, byte[]> dependencies) throws Failure {
        Objects.requireNonNull(source);
        try (source) {
            if (!(source instanceof com.naocraftlab.skins.client.ImportInput.Reader frozen)) {
                try (var captured = com.naocraftlab.skins.client.FrozenImportInput.capture(new FilterInputStream(source) {
                    @Override public void close() { }
                }); var reader = captured.open()) {
                    return admit(reader, dependencies);
                }
            }
            if (frozen.kind() == com.naocraftlab.skins.client.ImportInput.Kind.PROJECT) return admitProject(frozen, dependencies);
            if (!CpmModelFileBindings.supported(Thread.currentThread().getContextClassLoader()))
                throw new Failure(Reason.CPM_UNAVAILABLE);
            ModelFile file;
            var input = new ParserInput(new FrozenReader(frozen));
            try { file = ModelFile.load("import", input); }
            catch (IOException failure) { throw new Failure(input.ioFailed ? Reason.IO_FAILED : Reason.INVALID_MODEL); }
            Metadata metadata;
            byte[] data = file.getDataBlock().clone();
            try { metadata = metadata(data, file); }
            catch (IOException failure) { throw new Failure(Reason.INVALID_MODEL); }
            var handle = new Handle(this, file, metadata.variant(), null, metadata.ownedSkin(), data);
            handle.metadata = metadata;
            return new Ready(handle);
        } catch (com.naocraftlab.skins.client.FrozenImportInput.Rejected failure) {
            throw new Failure(failure.reason() == com.naocraftlab.skins.client.FrozenImportInput.Rejection.INVALID
                    ? Reason.INVALID_MODEL : Reason.MODEL_UNSUPPORTED);
        } catch (IOException failure) {
            throw new Failure(Reason.IO_FAILED);
        } catch (Failure failure) {
            throw new Failure(failure.reason());
        } catch (RuntimeException | LinkageError failure) {
            throw new Failure(Reason.CPM_UNAVAILABLE);
        }
    }

    private Admission admitProject(com.naocraftlab.skins.client.ImportInput.Reader input,
            java.util.Map<String, byte[]> dependencies) throws Failure, IOException {
        if (!CpmProjectBindings.supported(Thread.currentThread().getContextClassLoader())
                || !CpmProjectTextureAccess.class.isAssignableFrom(com.tom.cpm.shared.editor.Generators.class))
            throw new Failure(Reason.CPM_UNAVAILABLE);
        var project = new com.tom.cpm.shared.editor.project.ProjectFile();
        Path file = input.file();
        try { project.load(file.toFile()).join(); }
        catch (java.util.concurrent.CompletionException failure) {
            throw new Failure(failure.getCause() instanceof FileNotFoundException ? Reason.IO_FAILED : Reason.INVALID_MODEL);
        }
        var editor = new com.tom.cpm.shared.editor.Editor();
        editor.setUI(new ProjectUi());
        editor.skinType = SkinType.UNKNOWN;
        for (var part : com.tom.cpm.shared.model.PlayerModelParts.VALUES)
            if (part != com.tom.cpm.shared.model.PlayerModelParts.CUSTOM_PART)
                editor.elements.add(new com.tom.cpm.shared.editor.elements.ModelElement(editor,
                        com.tom.cpm.shared.editor.elements.ElementType.ROOT_PART, part));
        try {
            CpmProjectImportScope.run(editor, 0, dependencies, () -> {
                CpmProjectImportScope.loadProject(editor, 0, project);
                return null;
            });
        } catch (CpmProjectImportScope.DependencyRequired required) {
            return new Dependency(required.link(), dependencyUri(required.nativeLink()));
        } catch (CpmProjectImportScope.UnsupportedData failure) { throw new Failure(Reason.MODEL_UNSUPPORTED); }
        catch (CpmProjectImportScope.MissingData failure) { throw new Failure(Reason.PORTABILITY_UNSUPPORTED); }
        catch (IOException failure) { throw new Failure(Reason.INVALID_MODEL); }
        SkinModel variant = editor.skinType == SkinType.UNKNOWN ? null
                : editor.skinType == SkinType.SLIM ? SkinModel.SLIM : SkinModel.CLASSIC;
        var handle = new Handle(this, null, variant, editor, false, null);
        if (CpmProjectImportScope.needsIdentity(editor)) {
            try { handle.projectIdentity = CpmProjectImportScope.Identity.capture(); }
            catch (CpmProjectImportScope.MissingData failure) { throw new Failure(Reason.PORTABILITY_UNSUPPORTED); }
        }
        var owned = new java.util.HashMap<String, byte[]>();
        dependencies.forEach((key, bytes) -> owned.put(key, bytes.clone()));
        handle.dependencies = java.util.Map.copyOf(owned);
        return new Ready(handle);
    }

    private static java.net.URI dependencyUri(com.tom.cpm.shared.definition.Link link) throws Failure {
        try {
            java.net.URL url = switch (link.getLoader()) {
                case "p" -> new PasteUrl().url(link.getPath());
                case "gh" -> new GithubUrl().url(link.getPath());
                case "git" -> new GistUrl().url(link.getPath());
                case "ms" -> new ModelsUrl().url(link.getPath());
                case "pb" -> new PastebinUrl().url(link.getPath());
                default -> throw new Failure(Reason.PORTABILITY_UNSUPPORTED);
            };
            var uri = url.toURI();
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null || uri.getPort() != -1 && uri.getPort() != 443
                    || "p".equals(link.getLoader()) && !"paste.tom5454.com".equals(uri.getHost()))
                throw new Failure(Reason.PORTABILITY_UNSUPPORTED);
            return uri;
        } catch (IOException | java.net.URISyntaxException | IllegalArgumentException failure) {
            throw new Failure(Reason.PORTABILITY_UNSUPPORTED);
        }
    }

    private static final class PasteUrl extends com.tom.cpm.shared.loaders.PasteResourceLoader {
        java.net.URL url(String path) throws IOException { return super.createURL(path); }
    }
    private static final class GithubUrl extends com.tom.cpm.shared.loaders.GithubRepoResourceLoader {
        java.net.URL url(String path) throws IOException { return super.createURL(path); }
    }
    private static final class GistUrl extends com.tom.cpm.shared.loaders.GistResourceLoader {
        java.net.URL url(String path) throws IOException { return super.createURL(path); }
    }
    private static final class ModelsUrl extends com.tom.cpm.shared.loaders.ModelsCDNResourceLoader {
        java.net.URL url(String path) throws IOException { return super.createURL(path); }
    }
    private static final class PastebinUrl extends com.tom.cpm.shared.loaders.PastebinResourceLoader {
        java.net.URL url(String path) throws IOException { return super.createURL(path); }
    }

    private static final class ProjectUi implements com.tom.cpl.gui.UI {
        public String i18nFormat(String key, Object... arguments) { return key; }
        public void displayMessagePopup(String title, String text, String close) { throw new UnsupportedOperationException(); }
        public void executeLater(Runnable action) { throw new UnsupportedOperationException(); }
        public void displayPopup(java.util.function.Function<com.tom.cpl.gui.Frame, com.tom.cpl.gui.elements.PopupPanel> factory) { throw new UnsupportedOperationException(); }
        public void displayConfirm(String title, String message, Runnable ok, Runnable cancel, String yes, String no) { throw new UnsupportedOperationException(); }
        public void onGuiException(String message, Throwable failure, boolean fatal) { throw new UnsupportedOperationException(); }
    }

    @SuppressWarnings("deprecation")
    private static Metadata metadata(byte[] data, ModelFile file) throws IOException, Failure {
        if (!(file instanceof com.naocraftlab.skins.compat.cpm.mixin.CpmModelFileBackupAccessor backup))
            throw new Failure(Reason.CPM_UNAVAILABLE);
        var input = new ByteArrayInputStream(data, 1, data.length - 1);
        var reader = new com.tom.cpm.shared.io.IOHelper(input);
        var parts = new java.util.ArrayList<RawPart>();
        SkinModel[] required = {null};
        Reason[] portability = {null};
        boolean[] ownedSkin = {false};
        while (true) {
            int start = data.length - input.available();
            byte[][] body = {null};
            com.tom.cpm.shared.definition.Link[] link = {null};
            var kind = reader.readObjectBlock(com.tom.cpm.shared.parts.ModelPartType.VALUES, (type, block) -> {
                body[0] = block.getDin().readAllBytes();
                block = new com.tom.cpm.shared.io.IOHelper(new ByteArrayInputStream(body[0]));
                if (type == com.tom.cpm.shared.parts.ModelPartType.SKIN_TYPE) {
                    var nativeType = ((com.tom.cpm.shared.parts.ModelPartSkinType) type.getFactory().create(block, null)).getSkinType();
                    SkinModel concrete = nativeType == SkinType.UNKNOWN ? null : nativeType == SkinType.SLIM ? SkinModel.SLIM : SkinModel.CLASSIC;
                    if (concrete != null) {
                        if (required[0] != null && required[0] != concrete) throw new IOException();
                        required[0] = concrete;
                    }
                }
                if (type == com.tom.cpm.shared.parts.ModelPartType.DEFINITION
                        || type == com.tom.cpm.shared.parts.ModelPartType.SKIN
                        || type == com.tom.cpm.shared.parts.ModelPartType.TEXTURE) {
                    var part = type.getFactory().create(block, null);
                    ownedSkin[0] |= ownedSkin(part);
                    Reason nested = nestedPortability(part);
                    if (nested != null) portability[0] = nested;
                }
                if (type == com.tom.cpm.shared.parts.ModelPartType.DEFINITION_LINK
                        || type == com.tom.cpm.shared.parts.ModelPartType.PACKAGE_LINK
                        || type == com.tom.cpm.shared.parts.ModelPartType.SKIN_LINK) {
                    var part = (com.tom.cpm.shared.parts.ModelPartLink) type.getFactory().create(block, null);
                    link[0] = part.getLink();
                    if (part.getLink().getLoader().equals("local")) {
                        if (backup.nclskins$modelBackup() == null || backup.nclskins$modelLink() == null
                                || !part.getLink().equals(backup.nclskins$modelLink())
                                || type == com.tom.cpm.shared.parts.ModelPartType.SKIN_LINK)
                            portability[0] = Reason.NONPORTABLE_OVERFLOW;
                    }
                    else if (!java.util.Set.of("p", "gh", "git", "ms", "pb").contains(part.getLink().getLoader()))
                        portability[0] = Reason.PORTABILITY_UNSUPPORTED;
                }
                return type;
            });
            parts.add(new RawPart(kind, java.util.Arrays.copyOfRange(data, start, data.length - input.available()), body[0], link[0]));
            if (kind == com.tom.cpm.shared.parts.ModelPartType.END) {
                if (portability[0] != null) throw new Failure(portability[0]);
                var local = parts.stream().filter(part -> part.link() != null && part.link().getLoader().equals("local")).toList();
                if (!local.isEmpty()) {
                    var nativeBackup = readBackup(backup.nclskins$modelBackup(), local.get(0).kind());
                    Reason nested = nestedPortability(nativeBackup);
                    if (nested != null) throw new Failure(nested);
                    ownedSkin[0] |= local.get(0).kind() == com.tom.cpm.shared.parts.ModelPartType.DEFINITION_LINK
                            ? ownedSkin(nativeBackup) : metadata(backup.nclskins$modelBackup(), file).ownedSkin();
                }
                return new Metadata(required[0], ownedSkin[0], java.util.List.copyOf(parts),
                        backup.nclskins$modelBackup() == null ? null : backup.nclskins$modelBackup().clone());
            }
        }
    }

    private static Reason nestedPortability(com.tom.cpm.shared.parts.IModelPart part) {
        if (part instanceof com.tom.cpm.shared.parts.ModelPartLink link) {
            if (link.getLink().getLoader().equals("local")) return Reason.NONPORTABLE_OVERFLOW;
            if (!java.util.Set.of("p", "gh", "git", "ms", "pb").contains(link.getLink().getLoader())) return Reason.PORTABILITY_UNSUPPORTED;
        }
        if (part instanceof com.naocraftlab.skins.compat.cpm.mixin.CpmModelDefinitionPartsAccessor accessor) {
            for (var child : java.util.List.copyOf(accessor.nclskins$modelParts())) {
                Reason reason = nestedPortability(child);
                if (reason != null) return reason;
            }
        }
        return null;
    }

    private record Metadata(SkinModel variant, boolean ownedSkin, java.util.List<RawPart> parts, byte[] backup) { }
    private record RawPart(com.tom.cpm.shared.parts.ModelPartType kind, byte[] raw, byte[] body,
            com.tom.cpm.shared.definition.Link link) { }

    @SuppressWarnings("deprecation")
    private static boolean ownedSkin(com.tom.cpm.shared.parts.IModelPart part) throws IOException {
        if (part instanceof com.tom.cpm.shared.parts.ModelPartDefinition) {
            if (!(part instanceof com.naocraftlab.skins.compat.cpm.mixin.CpmModelDefinitionPartsAccessor accessor))
                throw new IllegalStateException();
            boolean owned = false;
            for (var child : java.util.List.copyOf(accessor.nclskins$modelParts())) owned |= ownedSkin(child);
            return owned;
        }
        com.tom.cpm.shared.skin.TextureProvider provider;
        if (part instanceof com.tom.cpm.shared.parts.ModelPartSkin) {
            if (!(part instanceof com.naocraftlab.skins.compat.cpm.mixin.CpmModelSkinTextureAccessor accessor))
                throw new IllegalStateException();
            provider = accessor.nclskins$modelSkin();
        } else if (part instanceof com.tom.cpm.shared.parts.ModelPartTexture) {
            if (!(part instanceof com.naocraftlab.skins.compat.cpm.mixin.CpmModelTextureAccessor accessor))
                throw new IllegalStateException();
            if (accessor.nclskins$modelSheet() != com.tom.cpm.shared.model.TextureSheetType.SKIN) return false;
            provider = accessor.nclskins$modelTexture();
        } else return false;
        if (provider == null || provider.getImage() == null) throw new IOException();
        return true;
    }

    private static final class FrozenReader extends InputStream {
        private final com.naocraftlab.skins.client.ImportInput.Reader input;
        private long position;
        private boolean closed;
        private FrozenReader(com.naocraftlab.skins.client.ImportInput.Reader input) { this.input = input; }
        @Override public int read() throws IOException {
            byte[] value = new byte[1];
            return read(value, 0, 1) < 0 ? -1 : value[0] & 255;
        }
        @Override public int read(byte[] bytes, int offset, int count) throws IOException {
            if (closed) throw new IOException("Import reader closed");
            int read = input.readAt(position, bytes, offset, count);
            if (read > 0) position += read;
            return read;
        }
        @Override public void close() { closed = true; }
    }

    @Override public Preparation prepare(AdmittedModel model, Raster base, long revision) throws Failure {
        if (!(model instanceof Handle handle) || handle.owner != this) throw new Failure(Reason.CLOSED);
        synchronized (handle) {
            if (handle.closed) throw new Failure(Reason.CLOSED);
            if (handle.variant != null && base.variant() != handle.variant) throw new Failure(Reason.VARIANT_UNSUPPORTED);
            if (availability() != Availability.READY) throw new Failure(Reason.CPM_UNAVAILABLE);
            try {
                if (handle.project != null) {
                    if (handle.projectIdentity != null) handle.projectIdentity.requireCurrent();
                    var prepared = prepareProject(handle.project, revision, handle.dependencies, base);
                    if (handle.projectIdentity != null) handle.projectIdentity.requireCurrent();
                    return prepared.inline().isPresent() ? new Inline(prepared.inline().orElseThrow())
                            : new ProjectPublication(handle, prepared, revision);
                }
                ModelFile file = handle.file;
                if (!java.util.Arrays.equals(handle.data, file.getDataBlock())) throw new Failure(Reason.CONVERSION_FAILED);
                if (handle.metadata.parts().stream().anyMatch(part -> part.link() != null && part.link().getLoader().equals("local")))
                    return checkedPreparation(prepareLocal(handle, base, revision, handle.metadata.parts()));
                if (projectCarrier(handle.data, base) == null) return checkedPreparation(prepareOverflow(handle, base, revision, handle.metadata.parts()));
                Image image = new Image(base.argb(), 64);
                Raster[] result = {null};
                int[] terminals = {0};
                Exporter.convert(file, image, base.variant() == SkinModel.SLIM ? SkinType.SLIM : SkinType.DEFAULT,
                        converted -> {
                            terminals[0]++;
                            if (converted != null && converted.getWidth() == 64 && converted.getHeight() == 64)
                                result[0] = new Raster(base.variant(), converted.getData());
                        }, () -> terminals[0]++);
                if (terminals[0] != 1 || result[0] == null) throw new Failure(Reason.CONVERSION_FAILED);
                if (handle.closed) throw new Failure(Reason.CLOSED);
                return new Inline(result[0]);
            } catch (CpmProjectImportScope.MissingData failure) { throw new Failure(Reason.PORTABILITY_UNSUPPORTED); }
            catch (CpmProjectImportScope.UnsupportedData failure) { throw new Failure(Reason.MODEL_UNSUPPORTED); }
            catch (IOException failure) { throw new Failure(Reason.CONVERSION_FAILED); }
            catch (RuntimeException | LinkageError failure) { throw new Failure(Reason.CONVERSION_FAILED); }
        }
    }

    @SuppressWarnings("deprecation")
    private Preparation prepareLocal(Handle handle, Raster base, long revision, java.util.List<RawPart> parts) throws IOException, Failure {
        var first = parts.stream().filter(part -> part.link() != null && part.link().getLoader().equals("local")).findFirst().orElseThrow();
        var kind = first.kind();
        var local = parts.stream().filter(part -> part.link() != null && part.link().getLoader().equals("local") && part.kind() == kind).toList();
        readBackup(handle.metadata.backup(), kind);
        byte[] wire = handle.metadata.backup();
        byte[] body = java.util.Arrays.copyOfRange(wire, 1, wire.length - 2);
        byte[] replacement;
        if (kind == com.tom.cpm.shared.parts.ModelPartType.DEFINITION_LINK) {
            var writer = new com.tom.cpm.shared.io.IOHelper();
            try (var block = writer.writeNextObjectBlock(com.tom.cpm.shared.parts.ModelPartType.DEFINITION)) { block.write(body); }
            replacement = writer.toBytes();
        } else {
            var blocks = new ByteArrayInputStream(body);
            var nativeReader = new com.tom.cpm.shared.io.IOHelper(blocks);
            int end;
            do {
                end = body.length - blocks.available();
                var type = nativeReader.readObjectBlock(com.tom.cpm.shared.parts.ModelPartType.VALUES, (value, block) -> value);
                if (type == com.tom.cpm.shared.parts.ModelPartType.END) break;
            } while (true);
            replacement = java.util.Arrays.copyOf(body, end);
        }
        var expanded = replaceParts(parts, local, new RawPart(kind, replacement, null, null), true);
        Raster inline = parts.stream().anyMatch(part -> part.link() != null && part.link().getLoader().equals("local") && part.kind() != kind)
                ? null : projectCarrier(partBytes(expanded), base);
        if (handle.closed) throw new Failure(Reason.CLOSED);
        if (inline != null) return new Inline(inline);
        return new ModelPublication(handle, base, revision, wire, parts, local, kind, true);
    }

    @SuppressWarnings("deprecation")
    private static com.tom.cpm.shared.parts.IModelPart readBackup(byte[] wire, com.tom.cpm.shared.parts.ModelPartType kind) throws IOException {
        var input = new ByteArrayInputStream(wire);
        if (input.read() != 83) throw new IOException();
        var checksum = new com.tom.cpm.shared.io.ChecksumInputStream(input);
        var reader = new com.tom.cpm.shared.io.IOHelper(checksum);
        var result = kind == com.tom.cpm.shared.parts.ModelPartType.DEFINITION_LINK
                ? new com.tom.cpm.shared.parts.ModelPartDefinition(reader, null) : new PackageReader().read(reader);
        checksum.checkSum();
        return result;
    }

    private static final class PackageReader extends com.tom.cpm.shared.parts.ModelPartCollection.PackageLink {
        private PackageReader() throws IOException { super(packageLinkInput(), null); }
        private com.tom.cpm.shared.parts.IModelPart read(com.tom.cpm.shared.io.IOHelper reader) throws IOException { return super.load(reader, null); }
        private static com.tom.cpm.shared.io.IOHelper packageLinkInput() throws IOException {
            var writer = new com.tom.cpm.shared.io.IOHelper();
            new com.tom.cpm.shared.definition.Link("p:synthetic").write(writer);
            return writer.flip();
        }
    }

    private Preparation checkedPreparation(Preparation prepared) throws Failure {
        Preparation probe = prepared;
        try {
            while (probe instanceof Publication publication) {
                Preparation next = publication.link("synthetic");
                if (probe != prepared) publication.close();
                probe = next;
            }
            return prepared;
        } catch (Failure | RuntimeException failure) {
            if (probe instanceof Publication publication) publication.close();
            if (prepared instanceof Publication publication) publication.close();
            throw failure;
        }
    }

    @SuppressWarnings("deprecation")
    private Preparation prepareOverflow(Handle handle, Raster base, long revision, java.util.List<RawPart> parts) throws IOException, Failure {
        var definitions = parts.stream().filter(part -> part.kind() == com.tom.cpm.shared.parts.ModelPartType.DEFINITION).toList();
        if (!definitions.isEmpty()) {
            var definition = definitions.get(0);
            return new ModelPublication(handle, base, revision, checkedBody(definition.body()), parts, java.util.List.of(definition),
                    com.tom.cpm.shared.parts.ModelPartType.DEFINITION_LINK, false);
        }
        var largest = new java.util.ArrayList<RawPart>();
        var span = new java.util.ArrayList<RawPart>();
        int largestBytes = 0, spanBytes = 0;
        for (var part : parts) {
            if (part.link() != null || part.kind() == com.tom.cpm.shared.parts.ModelPartType.END
                    || part.kind() == com.tom.cpm.shared.parts.ModelPartType.SKIN_TYPE) {
                if (spanBytes > largestBytes) { largest = new java.util.ArrayList<>(span); largestBytes = spanBytes; }
                span.clear(); spanBytes = 0;
            } else { span.add(part); spanBytes += part.raw().length; }
        }
        if (largest.isEmpty()) throw new Failure(Reason.PORTABILITY_UNSUPPORTED);
        var writer = new com.tom.cpm.shared.io.IOHelper();
        for (var part : largest) writer.write(part.raw());
        writer.writeObjectBlock(com.tom.cpm.shared.parts.ModelPartEnd.END);
        return new ModelPublication(handle, base, revision, checkedBody(writer.toBytes()), parts, java.util.List.copyOf(largest),
                com.tom.cpm.shared.parts.ModelPartType.PACKAGE_LINK, false);
    }

    private static byte[] checkedBody(byte[] body) throws IOException {
        var bytes = new ByteArrayOutputStream(); bytes.write(83);
        try (var checksum = new com.tom.cpm.shared.io.ChecksumOutputStream(bytes)) { checksum.write(body); }
        return bytes.toByteArray();
    }

    private static byte[] partBytes(java.util.List<RawPart> parts) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (var part : parts) bytes.write(part.raw());
        return checkedBody(bytes.toByteArray());
    }

    private static java.util.List<RawPart> replaceParts(java.util.List<RawPart> parts, java.util.List<RawPart> replaced,
            RawPart replacement, boolean everyOccurrence) {
        var result = new java.util.ArrayList<RawPart>();
        boolean written = false;
        for (var part : parts) {
            if (replaced.contains(part)) {
                if (!written || everyOccurrence) { result.add(replacement); written = true; }
            } else result.add(part);
        }
        return java.util.List.copyOf(result);
    }

    private final class ModelPublication implements Publication {
        private final Handle handle;
        private Raster base;
        private final long revision;
        private byte[] wire;
        private java.util.List<RawPart> parts, replaced;
        private final boolean everyOccurrence;
        private final com.tom.cpm.shared.parts.ModelPartType kind;
        private final SkinModel variant;
        private ModelPublication(Handle handle, Raster base, long revision, byte[] wire, java.util.List<RawPart> parts,
                java.util.List<RawPart> replaced, com.tom.cpm.shared.parts.ModelPartType kind, boolean everyOccurrence) {
            this.handle = handle; this.base = base; this.revision = revision; this.wire = wire.clone();
            this.parts = parts; this.everyOccurrence = everyOccurrence;
            this.replaced = replaced; this.kind = kind; this.variant = base.variant();
        }
        public SkinModel variant() { return variant; }
        public long revision() { return revision; }
        public byte[] wire() {
            synchronized (handle) {
                if (wire == null || handle.closed) throw new IllegalStateException("Closed preparation");
                return wire.clone();
            }
        }
        @SuppressWarnings("deprecation")
        public Preparation link(String confirmedPasteId) throws Failure {
            synchronized (handle) {
                if (wire == null || handle.closed) throw new Failure(Reason.CLOSED);
                if (confirmedPasteId == null || confirmedPasteId.isEmpty() || confirmedPasteId.length() > 253
                        || !confirmedPasteId.chars().allMatch(c -> c >= 33 && c <= 126 && c != ':'))
                    throw new Failure(Reason.CONVERSION_FAILED);
                try {
                    var link = new com.tom.cpm.shared.definition.Link("p", confirmedPasteId);
                    var writer = new com.tom.cpm.shared.io.IOHelper();
                    writer.writeObjectBlock(kind == com.tom.cpm.shared.parts.ModelPartType.DEFINITION_LINK
                            ? new com.tom.cpm.shared.parts.ModelPartDefinitionLink(link)
                            : new com.tom.cpm.shared.parts.ModelPartCollection().toLink(link));
                    var updated = replaceParts(parts, replaced, new RawPart(kind, writer.toBytes(), null, link), everyOccurrence);
                    if (updated.stream().anyMatch(part -> part.link() != null && part.link().getLoader().equals("local")))
                        return prepareLocal(handle, base, revision, updated);
                    Raster result = projectCarrier(partBytes(updated), base);
                    if (handle.closed) throw new Failure(Reason.CLOSED);
                    return result == null ? prepareOverflow(handle, base, revision, updated) : new Inline(result);
                } catch (IOException | RuntimeException | LinkageError failure) { throw new Failure(Reason.CONVERSION_FAILED); }
            }
        }
        public void close() { synchronized (handle) { wire = null; parts = null; replaced = null; base = null; } }
    }

    public ProjectPreparation prepareProject(com.tom.cpm.shared.editor.Editor editor, long revision,
            java.util.Map<String, byte[]> dependencies, Raster base) throws IOException {
        if (base == null || editor == null || editor.skinType == null) throw new CpmProjectImportScope.MissingData();
        SkinType type = base.variant() == SkinModel.SLIM ? SkinType.SLIM : SkinType.DEFAULT;
        if (editor.skinType != SkinType.UNKNOWN && editor.skinType != type) throw new CpmProjectImportScope.UnsupportedData();
        if (availability() != Availability.READY) throw new CpmProjectImportScope.UnsupportedData();
        return CpmProjectImportScope.run(editor, revision, dependencies, () -> {
            var vanillaSkin = editor.vanillaSkin;
            var skinType = editor.skinType;
            try {
                editor.vanillaSkin = new Image(base.argb(), 64);
                editor.skinType = type;
                var definition = CpmProjectImportScope.compile(editor, revision);
                byte[] compiled = projectBytes(type, definition, false);
                byte[] wire = projectBytes(type, definition, true);
                return new ProjectPreparation(this, base, compiled, wire, projectCarrier(compiled, base));
            } finally {
                editor.vanillaSkin = vanillaSkin;
                editor.skinType = skinType;
            }
        });
    }

    private final class ProjectPublication implements Publication {
        private final Handle handle;
        private final SkinModel variant;
        private final long revision;
        private ProjectPreparation preparation;
        private ProjectPublication(Handle handle, ProjectPreparation preparation, long revision) {
            this.handle = handle;
            this.preparation = preparation;
            this.variant = preparation.base.variant();
            this.revision = revision;
        }
        public SkinModel variant() { return variant; }
        public long revision() { return revision; }
        public byte[] wire() {
            synchronized (handle) {
                if (preparation == null || handle.closed) throw new IllegalStateException("Closed preparation");
                return preparation.wire();
            }
        }
        public Preparation link(String confirmedPasteId) throws Failure {
            synchronized (handle) {
                if (preparation == null || handle.closed || handle.owner != CpmModelFileAdapter.this)
                    throw new Failure(Reason.CLOSED);
                try {
                    if (handle.projectIdentity != null) handle.projectIdentity.requireCurrent();
                    return new Inline(linkProject(preparation, new com.tom.cpm.shared.definition.Link("p:" + confirmedPasteId)));
                } catch (IOException | RuntimeException | LinkageError failure) {
                    throw new Failure(Reason.CONVERSION_FAILED);
                }
            }
        }
        public void close() { synchronized (handle) { preparation = null; } }
    }

    @SuppressWarnings("deprecation")
    public Raster linkProject(ProjectPreparation preparation, com.tom.cpm.shared.definition.Link link) throws IOException {
        if (preparation.owner != this || preparation.inline != null) throw new CpmProjectImportScope.UnsupportedData();
        String value = Objects.requireNonNull(link).toString();
        if (!java.util.Set.of("p", "gh", "git", "ms", "pb").contains(link.getLoader())
                || value.length() > 255 || link.getPath().isEmpty()
                || !value.chars().allMatch(c -> c >= 33 && c <= 126)) throw new CpmProjectImportScope.UnsupportedData();
        var bytes = new ByteArrayOutputStream();
        bytes.write(83);
        try (var checksum = new com.tom.cpm.shared.io.ChecksumOutputStream(bytes)) {
            var writer = new com.tom.cpm.shared.io.IOHelper(checksum);
            writer.writeObjectBlock(new com.tom.cpm.shared.parts.ModelPartSkinType(
                    preparation.base.variant() == SkinModel.SLIM ? SkinType.SLIM : SkinType.DEFAULT));
            writer.writeObjectBlock(new com.tom.cpm.shared.parts.ModelPartDefinitionLink(link));
            writer.writeObjectBlock(com.tom.cpm.shared.parts.ModelPartEnd.END);
        }
        Raster result = projectCarrier(bytes.toByteArray(), preparation.base);
        if (result == null) throw new CpmProjectImportScope.UnsupportedData();
        return result;
    }

    @SuppressWarnings("deprecation")
    private static byte[] projectBytes(SkinType type, com.tom.cpm.shared.parts.ModelPartDefinition definition,
            boolean wire) throws IOException {
        var bytes = new ByteArrayOutputStream();
        bytes.write(83);
        try (var checksum = new com.tom.cpm.shared.io.ChecksumOutputStream(bytes)) {
            var writer = new com.tom.cpm.shared.io.IOHelper(checksum);
            if (wire) definition.writePackage(writer);
            else {
                writer.writeObjectBlock(new com.tom.cpm.shared.parts.ModelPartSkinType(type));
                definition.writeBlocks(writer);
                writer.writeObjectBlock(com.tom.cpm.shared.parts.ModelPartEnd.END);
            }
        }
        return bytes.toByteArray();
    }

    private Raster projectCarrier(byte[] payload, Raster base) throws IOException {
        var template = MinecraftClientAccess.get().getDefinitionLoader().getTemplate();
        var image = new Image(base.argb(), 64);
        int channel = base.variant() == SkinModel.SLIM ? SkinType.SLIM.getChannel() : SkinType.DEFAULT.getChannel();
        try (var writer = new com.tom.cpm.shared.io.SkinDataOutputStream(image, template, channel)) {
            writer.write(payload);
        } catch (EOFException overflow) { return null; }
        byte[] actual = new com.tom.cpm.shared.io.SkinDataInputStream(image, template, channel).readNBytes(payload.length);
        if (!java.util.Arrays.equals(payload, actual)) throw new CpmProjectImportScope.InvalidData();
        return new Raster(base.variant(), image.getData());
    }

    public static final class ProjectPreparation {
        private final CpmModelFileAdapter owner;
        private final Raster base;
        private final byte[] compiled, wire;
        private final Raster inline;
        private ProjectPreparation(CpmModelFileAdapter owner, Raster base, byte[] compiled, byte[] wire, Raster inline) {
            this.owner = owner; this.base = base; this.compiled = compiled; this.wire = wire; this.inline = inline;
        }
        public byte[] compiled() { return compiled.clone(); }
        public byte[] wire() { return wire.clone(); }
        public java.util.Optional<Raster> inline() { return java.util.Optional.ofNullable(inline); }
    }


    private static final class ParserInput extends FilterInputStream {
        private boolean ioFailed;
        private ParserInput(InputStream input) { super(input); }
        @Override public int read() throws IOException {
            try { return in.read(); }
            catch (IOException failure) { ioFailed = true; throw failure; }
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            try { return in.read(bytes, offset, length); }
            catch (IOException failure) { ioFailed = true; throw failure; }
        }
        @Override public long skip(long count) throws IOException {
            try { return in.skip(count); }
            catch (IOException failure) { ioFailed = true; throw failure; }
        }
        @Override public int available() throws IOException {
            try { return in.available(); }
            catch (IOException failure) { ioFailed = true; throw failure; }
        }
        @Override public synchronized void reset() throws IOException {
            try { in.reset(); }
            catch (IOException failure) { ioFailed = true; throw failure; }
        }
    }

    private static final class Handle implements AdmittedModel {
        private final CpmModelFileAdapter owner;
        private ModelFile file;
        private com.tom.cpm.shared.editor.Editor project;
        private CpmProjectImportScope.Identity projectIdentity;
        private final SkinModel variant;
        private final boolean ownedSkin;
        private byte[] data;
        private Metadata metadata;
        private java.util.Map<String, byte[]> dependencies = java.util.Map.of();
        private boolean closed;
        private Handle(CpmModelFileAdapter owner, ModelFile file, SkinModel variant, com.tom.cpm.shared.editor.Editor project,
                boolean ownedSkin, byte[] data) {
            this.owner = owner; this.file = file; this.variant = variant; this.project = project;
            this.ownedSkin = ownedSkin; this.data = data;
        }
        @Override public java.util.Optional<SkinModel> requiredVariant() { return java.util.Optional.ofNullable(variant); }
        @Override public synchronized java.util.Optional<Raster> automaticBase(SkinModel preferred) throws Failure {
            if (closed) throw new Failure(Reason.CLOSED);
            if (!ownedSkin && (project == null || !project.textures.get(com.tom.cpm.shared.model.TextureSheetType.SKIN).isEdited()))
                return java.util.Optional.empty();
            SkinModel selected = variant == null ? preferred : variant;
            var image = (selected == SkinModel.SLIM ? SkinType.SLIM : SkinType.DEFAULT).getSkinTexture();
            return java.util.Optional.of(new Raster(selected, image.getData()));
        }
        @Override public synchronized void close() {
            closed = true; file = null; project = null; projectIdentity = null; data = null; metadata = null; dependencies = java.util.Map.of();
        }
    }
}
