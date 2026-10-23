package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test

import javax.tools.ToolProvider
import java.nio.file.Files
import static org.junit.jupiter.api.Assertions.*

class WaveyCapesGeometryAbiTest {
    @Test
    void genuineStyleAndLogicalUvBranchesCannotFallThroughOrInvert() {
        WaveyCapesPassAbi.verifyGeometryDispatch(fixture(false, false, false))
        assertThrows(IllegalStateException) { WaveyCapesPassAbi.verifyGeometryDispatch(fixture(true, false, false)) }
        assertThrows(IllegalStateException) { WaveyCapesPassAbi.verifyGeometryDispatch(fixture(false, true, false)) }
        assertThrows(IllegalStateException) { WaveyCapesPassAbi.verifyGeometryDispatch(fixture(false, false, true)) }
    }

    private static byte[] fixture(boolean wrongStyle, boolean invertedUv, boolean fallthrough) {
        File directory = new File('build/test-data/wavey-geometry-abi/' + UUID.randomUUID())
        directory.mkdirs()
        String style = wrongStyle ? 'BLOCKY' : 'SMOOTH'
        String uv = invertedUv ? '!renderer.vanillaUvValues()' : 'renderer.vanillaUvValues()'
        String dispatch = fallthrough ? "if (current == CapeStyle.${style} && ${uv}) renderSmoothCape(); renderer.render();"
                : "if (current == CapeStyle.${style} && ${uv}) renderSmoothCape(); else renderer.render();"
        Map<String, String> sources = [
                'com/naocraftlab/skins/buildlogic/probe/geometry/versionless/CapeStyle.java':
                        'package com.naocraftlab.skins.buildlogic.probe.geometry.versionless; public enum CapeStyle { SMOOTH, BLOCKY }',
                'com/naocraftlab/skins/buildlogic/probe/geometry/render/CapeRenderer.java':
                        'package com.naocraftlab.skins.buildlogic.probe.geometry.render; public interface CapeRenderer { boolean vanillaUvValues(); void render(); }',
                'com/naocraftlab/skins/buildlogic/probe/geometry/render/CustomCapeRenderer.java': """
                    package com.naocraftlab.skins.buildlogic.probe.geometry.render;
                    import com.naocraftlab.skins.buildlogic.probe.geometry.versionless.CapeStyle;
                    public final class CustomCapeRenderer {
                        public static CapeStyle current = CapeStyle.SMOOTH;
                        private void renderSmoothCape() {}
                        public void render(CapeRenderer renderer) { ${dispatch} }
                    }
                """.toString()]
        List<String> arguments = ['-proc:none', '-d', directory.absolutePath]
        sources.each { String relative, String content ->
            File file = new File(directory, relative); file.parentFile.mkdirs(); file.text = content
            arguments.add(file.absolutePath)
        }
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream()
        assertEquals(0, ToolProvider.systemJavaCompiler.run(null, diagnostics, diagnostics, arguments as String[]), diagnostics.toString())
        directory = WaveyCapesCapturePortTest.bindCompiledFixture(directory,
                'com/naocraftlab/skins/buildlogic/probe/geometry', 'dev/tr7zw/waveycapes')
        new URLClassLoader([directory.toURI().toURL()] as URL[], ClassLoader.platformClassLoader).withCloseable { loader ->
            Class<?> renderer = loader.loadClass('dev.tr7zw.waveycapes.render.CapeRenderer')
            assertTrue(renderer.isInterface())
            loader.loadClass('dev.tr7zw.waveycapes.render.CustomCapeRenderer').getMethod('render', renderer)
        }
        Files.readAllBytes(new File(directory, 'dev/tr7zw/waveycapes/render/CustomCapeRenderer.class').toPath())
    }
}
