package com.naocraftlab.skins.compat.cpm;

import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.util.ModelDescription.CopyProtection;
import com.tom.cpm.shared.definition.Link;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.config.ResourceLoader.ResourceEncoding;
import com.tom.cpm.shared.parts.ModelPartDefinition;
import com.naocraftlab.skins.compat.cpm.mixin.CpmProjectExporterInvoker;
import java.io.*;
import java.util.*;

public final class CpmProjectImportScope {
    private static final ThreadLocal<CpmProjectImportScope> CURRENT = new ThreadLocal<>();
    private final Editor editor;
    private final long revision;
    private final Map<String,byte[]> dependencies;
    private final List<InputStream> readers = new ArrayList<>();

    private CpmProjectImportScope(Editor editor, long revision, Map<String,byte[]> dependencies) throws IOException {
        this.editor=Objects.requireNonNull(editor);this.revision=revision;
        if(dependencies.size()>16)throw new MissingData();
        var copy=new HashMap<String,byte[]>();long bytes=0;
        for(var entry:dependencies.entrySet()) {
            bytes+=entry.getValue().length;if(bytes>128L*1024*1024)throw new MissingData();
            copy.put(Objects.requireNonNull(entry.getKey()),entry.getValue().clone());
        }
        this.dependencies=Map.copyOf(copy);
    }
    public interface Invocation<T> { T run() throws IOException; }
    public static <T> T run(Editor editor,long revision,Map<String,byte[]> dependencies,Invocation<T> action) throws IOException {
        if(CURRENT.get()!=null)throw new IOException("REENTRANT_PROJECT_IMPORT");
        var frame=new CpmProjectImportScope(editor,revision,dependencies);CURRENT.set(frame);
        try { if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();return action.run(); }
        catch(DependencyRequired | MissingData | UnsupportedData | InterruptedIOException failure) { throw failure; }
        catch(IOException | RuntimeException failure) { throw new InvalidData(); }
        finally { try { for(var reader:frame.readers)reader.close(); } finally { CURRENT.remove(); } }
    }
    public static boolean owns(Editor editor) { var frame=CURRENT.get();return frame!=null && frame.editor==editor; }
    public static InputStream template(Editor editor,Link link,ResourceEncoding encoding,ModelDefinition definition) throws IOException {
        var frame=CURRENT.get();
        if(frame==null || frame.editor!=editor || encoding!=ResourceEncoding.NO_ENCODING || definition!=null)throw new MissingData();
        byte[] bytes=frame.dependencies.get(link.toString());
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
        if (bytes == null) throw new DependencyRequired(link);
        if (frame.readers.size() >= 16) throw new MissingData();
        var reader=new ByteArrayInputStream(bytes) {
            @Override public synchronized void close() { buf=new byte[0];pos=count=mark=0; }
        };
        frame.readers.add(reader);return reader;
    }
    @SuppressWarnings("deprecation")
    public static ModelPartDefinition compile(Editor editor,long revision) throws IOException {
        var frame=CURRENT.get();if(frame==null || frame.editor!=editor || frame.revision!=revision)throw new MissingData();
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
        if(!CpmProjectBindings.supported(Thread.currentThread().getContextClassLoader())
                || !CpmProjectExporterInvoker.class.isAssignableFrom(com.tom.cpm.shared.editor.Exporter.class))throw new UnsupportedData();
        var encoding = editor.animEnc;
        var description = editor.description;
        boolean bind = needsIdentity(editor);
        var identity = bind ? Identity.capture() : null;
        try {
            if (encoding == null && editor.animations.stream().anyMatch(com.tom.cpm.shared.editor.anim.EditorAnim::isCustom))
                editor.animEnc = com.tom.cpm.shared.editor.util.GetFreeSkinSlots.getDefault(editor);
            if (bind) description.uuid = identity.uuid;
            ModelPartDefinition definition;
            try { definition = CpmProjectExporterInvoker.nclskins$prepareDefinition(editor); }
            catch (com.tom.cpm.shared.editor.ExportException failure) { throw new UnsupportedData(); }
            if (identity != null) identity.requireCurrent();
            return definition;
        } finally {
            editor.animEnc = encoding;
            if (bind) description.uuid = null;
        }
    }
    public static void loadProject(Editor editor,long revision,com.tom.cpm.shared.editor.project.IProject project) throws IOException {
        var frame=CURRENT.get();if(frame==null || frame.editor!=editor || frame.revision!=revision)throw new MissingData();
        if(!CpmProjectTextureAccess.class.isAssignableFrom(com.tom.cpm.shared.editor.Generators.class))throw new UnsupportedData();
        if(!com.tom.cpm.shared.editor.project.ProjectIO.loaders.containsKey(project.getJson("config.json").getInt("version")))throw new UnsupportedData();
        com.tom.cpm.shared.editor.project.ProjectIO.loadProject(editor,project);
    }
    static boolean needsIdentity(Editor editor) {
        return editor.description != null && editor.description.copyProtection == CopyProtection.UUID_LOCK
                && editor.description.uuid == null;
    }
    static final class Identity {
        private final java.lang.ref.WeakReference<net.minecraft.client.User> user;
        private final UUID uuid;
        private Identity(net.minecraft.client.User user) {
            this.user = new java.lang.ref.WeakReference<>(user);
            this.uuid = user.getProfileId();
        }
        static Identity capture() throws MissingData {
            var client = net.minecraft.client.Minecraft.getInstance();
            var user = client == null ? null : client.getUser();
            if (user == null || user.getProfileId() == null) throw new MissingData();
            return new Identity(user);
        }
        void requireCurrent() throws MissingData {
            var current = capture();
            if (user.get() == null || current.user.get() != user.get() || !current.uuid.equals(uuid)) throw new MissingData();
        }
    }
    public static final class DependencyRequired extends IOException {
        private static final long serialVersionUID = 1L;
        private final transient Link nativeLink;
        private DependencyRequired(Link link) { super("PROJECT_DEPENDENCY_REQUIRED"); nativeLink = link; }
        public String link() { return nativeLink.toString(); }
        Link nativeLink() { return nativeLink; }
    }
    public static final class UnsupportedData extends IOException { private static final long serialVersionUID=1L; public UnsupportedData(){super("UNSUPPORTED_PROJECT_DATA");} }
    public static final class InvalidData extends IOException { private static final long serialVersionUID=1L; public InvalidData(){super("INVALID_PROJECT_DATA");} }
    public static final class MissingData extends IOException { private static final long serialVersionUID=1L; public MissingData(){super("MISSING_PROJECT_DATA");} }
}
