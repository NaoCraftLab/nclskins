package com.naocraftlab.skins.buildlogic

import groovy.xml.MarkupBuilder

final class IdeaRunConfigurations {
    static final Map<String, String> LOADERS = [fabric: 'Fabric', forge: 'Forge', neoforge: 'NeoForge']

    static String taskName(Map target, String runKind) {
        String loader = LOADERS[target.loader.id.toString()]
        if (loader == null) throw new IllegalArgumentException("Unsupported loader ${target.loader.id}")
        if (!(runKind in ['Client', 'Server'])) throw new IllegalArgumentException("Unsupported run kind ${runKind}")
        String version = target.minecraft.version.toString().replaceAll('[^A-Za-z0-9]', '')
        "run${runKind}${loader}${version}"
    }

    static String configurationName(Map target, String runKind) {
        "${target.minecraft.version}:${target.loader.id}:run${runKind}"
    }

    static String fileName(Map target, String runKind) {
        configurationName(target, runKind).replaceAll('[^A-Za-z0-9]', '_') + '.xml'
    }

    static String render(Map target, String runKind) {
        StringWriter output = new StringWriter()
        MarkupBuilder xml = new MarkupBuilder(output)
        xml.component(name: 'ProjectRunConfigurationManager') {
            configuration(default: 'false', name: configurationName(target, runKind), type: 'GradleRunConfiguration', factoryName: 'Gradle', folderName: target.minecraft.version.toString()) {
                ExternalSystemSettings {
                    option(name: 'executionName')
                    option(name: 'externalProjectPath', value: '$PROJECT_DIR$')
                    option(name: 'externalSystemIdString', value: 'GRADLE')
                    option(name: 'scriptParameters', value: '')
                    option(name: 'taskDescriptions') { list() }
                    option(name: 'taskNames') { list { option(value: taskName(target, runKind)) } }
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

    private IdeaRunConfigurations() {}
}
