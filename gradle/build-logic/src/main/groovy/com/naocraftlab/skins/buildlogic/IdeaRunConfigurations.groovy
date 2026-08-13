package com.naocraftlab.skins.buildlogic

import groovy.xml.MarkupBuilder

final class IdeaRunConfigurations {
    static final String GENERATED_MARKER = 'NCL_SKINS_CATALOG_GENERATED_V2'
    static final Map<String, String> LOADERS = [fabric: 'Fabric', forge: 'Forge', neoforge: 'NeoForge']
    static final List<String> RUN_KINDS = RunLayout.RUN_KINDS
    static final Map<String, Integer> LOADER_ORDER = [fabric: 0, neoforge: 1, forge: 2].asImmutable()
    static final Map<String, Integer> KERNEL_ORDER = [
            craftbukkit: 0, spigot: 1, paper: 2, purpur: 3, folia: 4
    ].asImmutable()
    static final Map<String, Integer> MODE_ORDER = [standalone: 0, velocity: 1, bungeecord: 2].asImmutable()

    static String taskName(Map target, String runKind) {
        taskName(target, target.minecraft.version.toString(), runKind)
    }

    static String taskName(Map target, String minecraftVersion, String runKind) {
        String loader = LOADERS[target.loader.id.toString()]
        if (loader == null) throw new IllegalArgumentException("Unsupported loader ${target.loader.id}")
        if (!(runKind in RUN_KINDS)) throw new IllegalArgumentException("Unsupported run kind ${runKind}")
        String version = minecraftVersion.replaceAll('[^A-Za-z0-9]', '')
        "run${runKind}${loader}${version}"
    }

    static String configurationName(Map target, String runKind) {
        configurationName(target, target.minecraft.version.toString(), runKind)
    }

    static String configurationName(Map target, String minecraftVersion, String runKind) {
        String suffix = runKind == 'LicensedClient' ? 'runClientLicensed' : "run${runKind}"
        "${minecraftVersion}:${target.loader.id}:${suffix}"
    }

    static String fileName(Map target, String runKind) {
        fileName(target, target.minecraft.version.toString(), runKind)
    }

    static String fileName(Map target, String minecraftVersion, String runKind) {
        configurationName(target, minecraftVersion, runKind)
                .replaceAll('[^A-Za-z0-9]', '_') + '.xml'
    }

    static List<String> previousConfigurationNames(Map target, String runKind) {
        runKind == 'LicensedClient'
                ? ["${target.minecraft.version}:${target.loader.id}:runLicensedClient".toString(),
                   "${target.minecraft.version}:${target.loader.id}:runClient (licensed)".toString()]
                : []
    }

    static List<String> previousFileNames(Map target, String runKind) {
        previousConfigurationNames(target, runKind).collect {
            it.replaceAll('[^A-Za-z0-9]', '_') + '.xml'
        }
    }

    static String render(Map target, String runKind) {
        render(target, target.minecraft.version.toString(), runKind)
    }

    static String render(Map target, String minecraftVersion, String runKind) {
        StringWriter output = new StringWriter()
        MarkupBuilder xml = new MarkupBuilder(output)
        xml.mkp.comment(GENERATED_MARKER)
        xml.component(name: 'ProjectRunConfigurationManager') {
            configuration(default: 'false', name: configurationName(target, minecraftVersion, runKind), type: 'GradleRunConfiguration', factoryName: 'Gradle', folderName: displayFolder(minecraftVersion)) {
                ExternalSystemSettings {
                    option(name: 'executionName')
                    option(name: 'externalProjectPath', value: '$PROJECT_DIR$')
                    option(name: 'externalSystemIdString', value: 'GRADLE')
                    option(name: 'scriptParameters', value: '')
                    option(name: 'taskDescriptions') { list() }
                    option(name: 'taskNames') { list { option(value: taskName(target, minecraftVersion, runKind)) } }
                    option(name: 'vmOptions', value: '')
                }
                ExternalSystemDebugServerProcess('false')
                ExternalSystemReattachDebugProcess('true')
                DebugAllEnabled('false')
                RunAsTest('false')
                method(v: '2')
            }
        }
        output.toString() + '\n'
    }

    static String renderServerPlugin(Map topology) {
        StringWriter output = new StringWriter()
        MarkupBuilder xml = new MarkupBuilder(output)
        xml.mkp.comment(GENERATED_MARKER)
        xml.component(name: 'ProjectRunConfigurationManager') {
            configuration(default: 'false', name: ServerPluginRuntimeSupport.configurationName(topology),
                    type: 'GradleRunConfiguration', factoryName: 'Gradle',
                    folderName: displayFolder(topology.minecraft.toString())) {
                ExternalSystemSettings {
                    option(name: 'executionName')
                    option(name: 'externalProjectPath', value: '$PROJECT_DIR$')
                    option(name: 'externalSystemIdString', value: 'GRADLE')
                    option(name: 'scriptParameters', value: '')
                    option(name: 'taskDescriptions') { list() }
                    option(name: 'taskNames') {
                        list { option(value: ServerPluginRuntimeSupport.taskName(topology)) }
                    }
                    option(name: 'vmOptions', value: '')
                }
                ExternalSystemDebugServerProcess('false')
                ExternalSystemReattachDebugProcess('true')
                DebugAllEnabled('false')
                RunAsTest('false')
                method(v: '2')
            }
        }
        output.toString() + '\n'
    }

    static List<Map> orderedTargets(Map catalog) {
        (catalog.targets as List).collect { it as Map }.sort { Map left, Map right ->
            int version = compareVersionsDescending(left.minecraft.version.toString(),
                    right.minecraft.version.toString())
            version != 0 ? version : Integer.compare(
                    LOADER_ORDER.getOrDefault(left.loader.id.toString(), 99),
                    LOADER_ORDER.getOrDefault(right.loader.id.toString(), 99))
        }
    }

    static List<Map> orderedModRuntimes(Map catalog) {
        (catalog.targets as List<Map>).collectMany { Map target ->
            CatalogTools.targetRuntimeSpecs(target)
        }.sort { Map left, Map right ->
            int folder = compareVersionsDescending(
                    displayFolder(left.minecraftVersion.toString()),
                    displayFolder(right.minecraftVersion.toString()))
            if (folder != 0) return folder
            int loader = Integer.compare(
                    LOADER_ORDER.getOrDefault(left.target.loader.id.toString(), 99),
                    LOADER_ORDER.getOrDefault(right.target.loader.id.toString(), 99))
            if (loader != 0) return loader
            compareVersionsDescending(
                    left.minecraftVersion.toString(), right.minecraftVersion.toString())
        }
    }

    static List<Map> orderedTopologies(Map catalog) {
        (catalog.serverPluginTopologies as List).collect { it as Map }.sort { Map left, Map right ->
            int folder = compareVersionsDescending(
                    displayFolder(left.minecraft.toString()),
                    displayFolder(right.minecraft.toString()))
            if (folder != 0) return folder
            int mode = Integer.compare(MODE_ORDER.getOrDefault(left.mode.toString(), 99),
                    MODE_ORDER.getOrDefault(right.mode.toString(), 99))
            if (mode != 0) return mode
            int kernel = Integer.compare(
                    KERNEL_ORDER.getOrDefault(left.kernel.toString(), 99),
                    KERNEL_ORDER.getOrDefault(right.kernel.toString(), 99))
            kernel != 0 ? kernel : compareVersionsDescending(
                    left.minecraft.toString(), right.minecraft.toString())
        }
    }

    static List<String> orderedConfigurationNames(Map catalog) {
        List<Map> runtimes = orderedModRuntimes(catalog)
        List<Map> topologies = orderedTopologies(catalog)
        Set<String> folders = (runtimes.collect {
            displayFolder(it.minecraftVersion.toString())
        } + topologies.collect { displayFolder(it.minecraft.toString()) }) as Set
        List<String> orderedFolders = folders.sort { String left, String right ->
            compareVersionsDescending(left, right)
        }
        List<String> names = []
        orderedFolders.each { String folder ->
            runtimes.findAll {
                displayFolder(it.minecraftVersion.toString()) == folder
            }.each { Map runtime ->
                RUN_KINDS.each { String runKind -> names.add(configurationName(
                        runtime.target as Map,
                        runtime.minecraftVersion.toString(),
                        runKind)) }
            }
            topologies.findAll {
                displayFolder(it.minecraft.toString()) == folder
            }.each { Map topology ->
                names.add(ServerPluginRuntimeSupport.configurationName(topology))
            }
        }
        names
    }

    static Map<String, String> previousToCurrentNames(Map catalog) {
        Map<String, String> result = [:]
        (catalog.targets as List<Map>).each { Map target ->
            RUN_KINDS.each { String runKind ->
                previousConfigurationNames(target, runKind).each {
                    result[it] = configurationName(target, runKind)
                }
            }
        }
        (catalog.serverPluginTopologies as List<Map>).each { Map topology ->
            result[ServerPluginRuntimeSupport.previousConfigurationName(topology)] =
                    ServerPluginRuntimeSupport.configurationName(topology)
        }
        result
    }

    static int compareVersionsDescending(String left, String right) {
        List<Integer> leftParts = left.tokenize('.').collect { it as int }
        List<Integer> rightParts = right.tokenize('.').collect { it as int }
        int length = Math.max(leftParts.size(), rightParts.size())
        for (int index = 0; index < length; index++) {
            int leftValue = index < leftParts.size() ? leftParts[index] : 0
            int rightValue = index < rightParts.size() ? rightParts[index] : 0
            if (leftValue != rightValue) return Integer.compare(rightValue, leftValue)
        }
        0
    }

    static String displayFolder(String minecraftVersion) {
        minecraftVersion
    }

    private IdeaRunConfigurations() {}
}
