package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

final class AbiVerifierOutputTest {
    private static File javap() { new File(System.getProperty('java.home'), 'bin/javap') }

    @Test
    void inheritedLauncherNoticePreservesRealJavapSurfacesAndHashes() {
        String inheritedOptions = System.getenv('JAVA_TOOL_OPTIONS')
        ['java.lang.Runnable', 'java.lang.String', 'java.util.Map$Entry'].each { String name ->
            def raw = AbiVerifier.run([javap().absolutePath, '-p', '-s', '-classpath', '.', name])
            assertEquals(0, raw.exit)
            String emittedNotice = 'Picked up JAVA_TOOL_OPTIONS: ' + (inheritedOptions ?: '')
            if (inheritedOptions != null) assertTrue(raw.output.readLines().contains(emittedNotice))
            String withoutNotice = raw.output.readLines().findAll { it != emittedNotice }.join('\n') + '\n'
            String notice = 'Picked up JAVA_TOOL_OPTIONS: ' + (inheritedOptions ?: '-Djava.io.tmpdir=unit-fixture')
            String nested = null
            if (name.contains('$')) {
                def verbose = AbiVerifier.run([javap().absolutePath, '-p', '-s', '-v', '-classpath', '.', name])
                assertEquals(0, verbose.exit)
                nested = AbiVerifier.nestedAccess(verbose.output, name)
                assertEquals('public static', nested)
            }
            Map control = AbiVerifier.parseJavap(AbiVerifier.normalize(withoutNotice), name, nested)
            Map prefixed = AbiVerifier.parseJavap(AbiVerifier.normalize(notice + '\n' + withoutNotice), name, nested)
            assertEquals(control, prefixed)
            Map actual = AbiVerifier.resolveClass(javap(), '.', name, [:])
            assertEquals(control, actual)
            assertEquals(AbiVerifier.selectedHash(name, control, new ArrayList(control.members), []),
                    AbiVerifier.selectedHash(name, actual, new ArrayList(actual.members), []))
            assertEquals('public', actual.access.toString().tokenize().first())
            assertFalse(actual.members.isEmpty())
            if (name == 'java.lang.Runnable') assertEquals('interface', actual.finality)
            if (name == 'java.lang.String') {
                assertEquals('final', actual.finality)
                assertTrue(actual.interfaces.contains('java.io.Serializable'))
            }
        }
    }

    @Test
    void emptyLauncherOptionNoticePreservesDeclaration() {
        String declaration = 'public class Example {\n}\n'
        Map control = AbiVerifier.parseJavap(AbiVerifier.normalize(declaration), 'Example', null)
        assertEquals(control, AbiVerifier.parseJavap(
                AbiVerifier.normalize('Picked up JAVA_TOOL_OPTIONS: \n' + declaration), 'Example', null))
    }

    @Test
    void unknownDiagnosticsAndMalformedDeclarationsStayRejected() {
        String declaration = 'public class Example {\n}\n'
        ['unexpected diagnostic', 'Picked up JAVA_TOOL_OPTIONSX: value', 'Picked up _JAVA_OPTIONS: value'].each { String prefix ->
            def error = assertThrows(IllegalStateException) {
                AbiVerifier.parseJavap(AbiVerifier.normalize(prefix + '\n' + declaration), 'Example', null)
            }
            assertTrue(error.message.contains('cannot parse class declaration'))
        }
        assertThrows(IllegalStateException) { AbiVerifier.parseJavap(AbiVerifier.normalize('public Example\n'), 'Example', null) }
    }

    @Test
    void missingExecutableAndNonzeroJavapStayRejected() {
        assertThrows(IOException) { AbiVerifier.resolveClass(new File('/nonexistent/nclskins-javap'), '.', 'Example', [:]) }
        def error = assertThrows(IllegalStateException) { AbiVerifier.resolveClass(javap(), '.', 'nclskins.absent.NoSuchClass', [:]) }
        assertTrue(error.message.contains('cannot resolve'))
    }
}
