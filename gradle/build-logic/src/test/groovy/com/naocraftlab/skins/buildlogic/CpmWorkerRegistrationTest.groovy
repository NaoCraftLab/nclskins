package com.naocraftlab.skins.buildlogic

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

final class CpmWorkerRegistrationTest {


    @Test
    void extendableClassDeclarationAcceptsAccessWidenerWhitespace() {
        String owner = 'net/minecraft/client/model/geom/ModelPart'
        for (String separator : [' ', '\t', ' \t  ']) {
            String declaration = ['extendable', 'class', owner].join(separator)
            assertTrue(VerifyCpmIntegrationTask.declaresExtendableClass(
                    'accessWidener v1 named\r\n' + declaration + '\r\n', owner),
                    "Valid access widener separator: ${separator.inspect()}")
        }
        assertFalse(VerifyCpmIntegrationTask.declaresExtendableClass('extendable class ' + owner + 'Other', owner))
        assertFalse(VerifyCpmIntegrationTask.declaresExtendableClass('accessible class ' + owner, owner))
        assertFalse(VerifyCpmIntegrationTask.declaresExtendableClass('# extendable class ' + owner, owner))
        assertFalse(VerifyCpmIntegrationTask.declaresExtendableClass('extendable class ' + owner + ' extra', owner))
        assertFalse(VerifyCpmIntegrationTask.declaresExtendableClass('extendable field ' + owner, owner))
    }

    @Test
    void accessTransformerClassExtensionRequiresExactOwner() {
        String owner = 'net/minecraft/client/model/geom/ModelPart'
        String binaryName = 'net.minecraft.client.model.geom.ModelPart'
        for (String separator : [' ', '\t', ' \t  ']) {
            assertTrue(VerifyCpmIntegrationTask.declaresExtendableClass(
                    'public-f' + separator + binaryName + '\n', owner))
        }
        assertFalse(VerifyCpmIntegrationTask.declaresExtendableClass('public ' + binaryName, owner))
        assertFalse(VerifyCpmIntegrationTask.declaresExtendableClass('public-f ' + binaryName + 'Other', owner))
        assertFalse(VerifyCpmIntegrationTask.declaresExtendableClass('# public-f ' + binaryName, owner))
        assertFalse(VerifyCpmIntegrationTask.declaresExtendableClass('public-f ' + binaryName + ' field', owner))
    }





}
