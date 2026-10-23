package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.tree.ClassNode
import javax.tools.ToolProvider
import static org.junit.jupiter.api.Assertions.*

class WaveyCapesCapturePortTest {
    @Test
    void compiledPortCannotBorrowSourceLocationHandleOrBypassOwnerInvalidation() {
        verify(fixture('', 'textures', false))
        assertThrows(IllegalStateException) { verify(fixture('provider = CapeSource.OTHER;', 'textures', false)) }
        assertThrows(IllegalStateException) { verify(fixture('profileId = new UUID(0, 0);', 'textures', false)) }
        assertThrows(IllegalStateException) { verify(fixture('exactLocation = "nclskins:other";', 'textures', false)) }
        assertThrows(IllegalStateException) { verify(fixture('', 'otherTextures', false)) }
        assertThrows(IllegalStateException) { verify(fixture('', 'textures', true)) }
    }

    private static void verify(File directory) {
        Closure read = { String name ->
            ClassNode node = new ClassNode()
            new ClassReader(new File(directory, name + '.class').bytes).accept(node, 0)
            node
        }
        WaveyCapesNativeAbi.verifyCapturePort(read('com/naocraftlab/skins/buildlogic/probe/port/Appearance'), read('com/naocraftlab/skins/buildlogic/probe/port/Registry'), read('com/naocraftlab/skins/buildlogic/probe/port/ThreadGuard'))
    }

    private static File fixture(String overwrite, String registry, boolean overridesClose) {
        File directory = new File('build/test-data/wavey-capture-port/' + UUID.randomUUID())
        directory.mkdirs()
        Map<String, String> sources = [
                'com/naocraftlab/skins/client/TextureUseValidity.java': '''
                    package com.naocraftlab.skins.client;
                    public interface TextureUseValidity { TextureUseValidity INVALID = () -> false; boolean isValid(); }
                ''',
                'com/naocraftlab/skins/client/TextureRegistry.java': '''
                    package com.naocraftlab.skins.client;
                    public interface TextureRegistry { record TextureHandle(String location) {} }
                ''',
                'com/naocraftlab/skins/client/PlayerAppearanceSink.java': '''
                    package com.naocraftlab.skins.client;
                    public interface PlayerAppearanceSink { enum CapeSource { MINECRAFTCAPES, OTHER } }
                ''',
                'com/naocraftlab/skins/client/AbstractTextureRegistry.java': '''
                    package com.naocraftlab.skins.client;
                    public class AbstractTextureRegistry {
                        public TextureUseValidity capture(TextureRegistry.TextureHandle handle) { return TextureUseValidity.INVALID; }
                        public void close() {}
                    }
                ''',
                'com/naocraftlab/skins/buildlogic/probe/nativeclient/Minecraft.java': '''
                    package com.naocraftlab.skins.buildlogic.probe.nativeclient;
                    public class Minecraft {
                        public static Minecraft getInstance() { return new Minecraft(); }
                        public boolean isSameThread() { return true; }
                    }
                ''',
                'com/naocraftlab/skins/buildlogic/probe/port/Registry.java': """
                    package com.naocraftlab.skins.buildlogic.probe.port;
                    public class Registry extends com.naocraftlab.skins.client.AbstractTextureRegistry {
                        protected void checkClientThread() {
                            if (!com.naocraftlab.skins.buildlogic.probe.nativeclient.Minecraft.getInstance().isSameThread()) throw new IllegalStateException();
                        }
                        ${overridesClose ? 'public void close() {}' : ''}
                    }
                """.toString(),
                'com/naocraftlab/skins/buildlogic/probe/port/ThreadGuard.java': '''
                    package com.naocraftlab.skins.buildlogic.probe.port;
                    public final class ThreadGuard {
                        public static boolean isClientThread() {
                            var client = com.naocraftlab.skins.buildlogic.probe.nativeclient.Minecraft.getInstance();
                            return client != null && client.isSameThread();
                        }
                    }
                ''',
                'com/naocraftlab/skins/buildlogic/probe/port/Appearance.java': """
                    package com.naocraftlab.skins.buildlogic.probe.port;
                    import java.util.*;
                    import com.naocraftlab.skins.client.*;
                    import com.naocraftlab.skins.client.PlayerAppearanceSink.CapeSource;
                    import com.naocraftlab.skins.client.TextureRegistry.TextureHandle;
                    import com.naocraftlab.skins.buildlogic.probe.nativeclient.Minecraft;
                    public final class Appearance {
                        private final Map<ProviderCape,TextureHandle> providerCapes = new HashMap<>();
                        private final Registry textures = new Registry(), otherTextures = new Registry();
                        private static void checkClientThread(Minecraft client) {
                            if (!client.isSameThread()) throw new IllegalStateException();
                        }
                        public TextureUseValidity captureCapeTexture(UUID profileId, CapeSource provider, String exactLocation) {
                            checkClientThread(Minecraft.getInstance());
                            ${overwrite}
                            if (profileId == null || provider == null || exactLocation == null) return TextureUseValidity.INVALID;
                            TextureHandle handle = providerCapes.get(new ProviderCape(profileId, provider));
                            return handle != null && exactLocation.equals(handle.location()) ? ${registry}.capture(handle) : TextureUseValidity.INVALID;
                        }
                        private record ProviderCape(UUID profileId, CapeSource provider) {}
                    }
                """.toString()]
        List<String> arguments = ['-proc:none', '-d', directory.absolutePath]
        sources.each { String relative, String content ->
            File file = new File(directory, relative); file.parentFile.mkdirs(); file.text = content
            arguments.add(file.absolutePath)
        }
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream()
        assertEquals(0, ToolProvider.systemJavaCompiler.run(null, diagnostics, diagnostics, arguments as String[]), diagnostics.toString())
        directory = bindCompiledFixture(directory, 'com/naocraftlab/skins/buildlogic/probe/nativeclient', 'net/minecraft/client')
        new URLClassLoader([directory.toURI().toURL()] as URL[], ClassLoader.platformClassLoader).withCloseable { loader ->
            loader.loadClass('com.naocraftlab.skins.buildlogic.probe.port.Appearance').getDeclaredMethods()
        }
        directory
    }

    static File bindCompiledFixture(File directory, String owned, String external) {
        List<File> classes = []
        directory.eachFileRecurse { if (it.name.endsWith('.class')) classes.add(it) }
        File bound = new File(directory, 'bound')
        Remapper mapping = new Remapper() {
            @Override String map(String name) {
                name == owned || name.startsWith(owned + '/') ? external + name.substring(owned.length()) : name
            }
        }
        classes.each { File original ->
            ClassReader reader = new ClassReader(original.bytes)
            ClassWriter writer = new ClassWriter(0)
            reader.accept(new ClassRemapper(writer, mapping), 0)
            File output = new File(bound, mapping.map(reader.className) + '.class')
            output.parentFile.mkdirs()
            output.bytes = writer.toByteArray()
        }
        bound
    }
}
