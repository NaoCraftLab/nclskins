package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertThrows

class DefaultSkinParityVerifierTest {
    @Test
    void detectsSelectorAndModelDriftAcrossResourcePathShapes() {
        for (String prefix : ['', 'textures/']) {
            String bytecode = selector(prefix)
            DefaultSkinParityVerifier.verifySelector(bytecode)
            assertThrows(IllegalStateException) {
                DefaultSkinParityVerifier.verifySelector(bytecode.replace('Math.floorMod', 'Math.abs'))
            }
            assertThrows(IllegalStateException) {
                DefaultSkinParityVerifier.verifySelector(bytecode.replace('arraylength', 'bipush'))
            }
            assertThrows(IllegalStateException) {
                DefaultSkinParityVerifier.verifySelector(bytecode.replace('slim/alex', 'slim/steve'))
            }
            assertThrows(IllegalStateException) {
                DefaultSkinParityVerifier.verifySelector(bytecode.replaceFirst('Model.SLIM:', 'Model.WIDE:'))
            }
        }
    }

    private static String selector(String prefix) {
        String selection = '''
0: getstatic #1
3: aload_0
4: invokevirtual #2 // Method java/util/UUID.hashCode:()I
7: getstatic #1
10: arraylength
11: invokestatic #3 // Method java/lang/Math.floorMod:(II)I
14: aaload
15: areturn
'''
        ['slim', 'wide'].each { model ->
            ['alex', 'ari', 'efe', 'kai', 'makena', 'noor', 'steve', 'sunny', 'zuri'].each { name ->
                selection += "0: ldc #4 // String ${prefix}entity/player/${model}/${name}${prefix ? '.png' : ''}\n"
                selection += "0: getstatic #5 // Field Model.${model.toUpperCase(Locale.ROOT)}:LModel;\n"
            }
        }
        selection
    }
}
