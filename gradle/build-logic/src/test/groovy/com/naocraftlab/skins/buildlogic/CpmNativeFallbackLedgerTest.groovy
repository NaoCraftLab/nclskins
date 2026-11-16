package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import static org.junit.jupiter.api.Assertions.*

final class CpmNativeFallbackLedgerTest {
    @TempDir Path directory

    @Test void acceptsSeparateHiddenAndVisibleFallbackConsumers() {
        write(rows())
        VerifyCpmNativeMeasurementTask.verifyFallbackPolicy(directory.toFile())
    }

    @Test void rejectsMissingOrExtraFallbackConsumerPasses() {
        [0, 1, 3].each { count ->
            def ledger = rows()
            ledger.findAll { it.mode == 'worldless-draw' }.each {
                it.drawTerminals = count
                it.materials = count
                it.completions = count
            }
            write(ledger)
            assertThrows(IllegalStateException) {
                VerifyCpmNativeMeasurementTask.verifyFallbackPolicy(directory.toFile())
            }
        }
    }

    @Test void preservesMaterialRetirementAndMissingRequiredControls() {
        [
            ['worldless-draw', 'materials', 1],
            ['worldless-draw', 'completions', 1],
            ['worldless-draw', 'displayKind', 'CPM'],
            ['worldless-draw', 'nativeScale', 0],
            ['worldless-draw', 'storedFullFactor', 1],
            ['missing-ears-live', 'vertices', 0],
            ['missing-ears-live', 'definitionBindings', 0],
            ['missing-ears-live', 'firstCreates', 1],
            ['worldless-stale', 'materials', 1],
            ['worldless-failed', 'completions', 1],
            ['worldless-pending', 'drawTerminals', 1]
        ].each { mutation ->
            def ledger = rows()
            ledger.find { it.mode == mutation[0] }[mutation[1]] = mutation[2]
            write(ledger)
            assertThrows(IllegalStateException) {
                VerifyCpmNativeMeasurementTask.verifyFallbackPolicy(directory.toFile())
            }
        }
        def missing = rows()
        missing.remove(0)
        write(missing)
        assertThrows(IllegalStateException) {
            VerifyCpmNativeMeasurementTask.verifyFallbackPolicy(directory.toFile())
        }
        def duplicated = rows()
        duplicated[0] = new LinkedHashMap(duplicated[1])
        write(duplicated)
        assertThrows(IllegalStateException) {
            VerifyCpmNativeMeasurementTask.verifyFallbackPolicy(directory.toFile())
        }
    }

    private void write(List<Map> ledger) {
        directory.resolve('fallback-policy.json').toFile().text = JsonOutput.toJson(ledger)
    }

    private static List<Map> rows() {
        List<Map> ledger = []
        ['gallery', 'editor', 'providers'].each { surface ->
            ledger.add([surface: surface, mode: 'worldless-draw', drawTerminals: 2,
                        materials: 2, completions: 2, displayKind: 'ORDINARY',
                        nativeScale: 80, storedFullFactor: 0.4])
            ledger.add([surface: surface, mode: 'missing-ears-live', vertices: 100,
                        definitionBindings: 1, firstCreates: 0, displayKind: 'ORDINARY'])
        }
        ['worldless-stale', 'worldless-failed', 'worldless-pending'].each { mode ->
            ledger.add([surface: 'providers', mode: mode, completions: 0, materials: 0,
                        drawTerminals: mode == 'worldless-failed' ? 1 : 0, displayKind: 'UNCONFIRMED'])
        }
        return ledger
    }
}
