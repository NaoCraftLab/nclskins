package com.naocraftlab.skins.buildlogic

import groovy.json.JsonOutput

import java.nio.charset.StandardCharsets
import java.nio.file.Files


final class ServerPluginReleaseState {
    static Map compute(File repository, Map currentCatalog, String requestedVersion) {
        Map selection = ServerPluginVersion.select(requestedVersion,
                CatalogTools.loadVersion(repository), ServerPluginVersion.load(repository))
        [schemaVersion: 2, sealed: true] + selection + [
                publish: selection.componentKind != 'mod-only',
                reason: selection.componentKind,
                activeVersion: selection.functionalVersion,
                protocolIds: currentCatalog.serverPlugin.protocols,
                matrixId: currentCatalog.serverPlugin.matrixId]
    }


    static File write(File destination, Map state) {
        Files.createDirectories(destination.toPath().parent)
        Files.writeString(
                destination.toPath(),
                JsonOutput.prettyPrint(JsonOutput.toJson(state)) + '\n',
                StandardCharsets.UTF_8)
        destination
    }


    static int compareVersions(String left, String right) {
        NclReleaseVersion.parse(left).compareTo(NclReleaseVersion.parse(right))
    }



    private ServerPluginReleaseState() {
    }
}
