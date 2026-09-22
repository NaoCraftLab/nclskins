package com.naocraftlab.skins.buildlogic

import org.gradle.api.Project
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

class ReceiptEnvironment {
    static Map capture(Project project, Map catalog) {
        def start = project.gradle.startParameter
        Set allowed = ['nclskinsPrismForceBuild', 'nclskinsPrismCleanBuild'] as Set
        File userHome = project.gradle.gradleUserHomeDir
        List<File> initDirectories = [new File(userHome, 'init.d'), new File(project.gradle.gradleHomeDir, 'init.d')]
        boolean externalInit = initDirectories.any { directory ->
            directory.exists() && directory.listFiles().any { it.name.endsWith('.gradle') || it.name.endsWith('.gradle.kts') }
        }
        if (start.projectProperties.keySet().any { !allowed.contains(it) } ||
                start.systemPropertiesArgs.keySet().any { !(it in ['user.variant', 'file.encoding', 'user.country', 'user.language']) } || !start.initScripts.isEmpty() ||
                !start.excludedTaskNames.isEmpty() || start.refreshDependencies || start.offline ||
                new File(userHome, 'gradle.properties').exists() || externalInit ||
                new File(userHome, 'init.gradle').exists() || new File(userHome, 'init.gradle.kts').exists() ||
                System.getenv().keySet().any { it.startsWith('ORG_GRADLE_PROJECT_') ||
                    it in ['JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'GRADLE_OPTS'] }) {
            throw new IllegalStateException('Unsupported build settings for reusable Prism evidence: ' + [projectProperties: start.projectProperties.keySet(), systemProperties: start.systemPropertiesArgs.keySet(), initScripts: start.initScripts.size(), excludedTasks: start.excludedTaskNames, refresh: start.refreshDependencies, offline: start.offline, externalInit: externalInit])
        }
        Set<String> homes = [System.getProperty('java.home')] as Set
        catalog.targets.each { homes.add(TargetRuntime.resolveJavaHome(it.java.buildJdk as int)) }
        JavaToolchainService toolchains = project.subprojects.first().extensions.getByType(JavaToolchainService)
        [17, catalog.serverPlugin.packaging.buildJdk as int].unique().each { int version ->
            homes.add(toolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(version)) }
                    .get().metadata.installationPath.asFile.canonicalPath)
        }
        [systemProperties: start.systemPropertiesArgs.sort(), gradle: project.gradle.gradleVersion, gradleHome: project.gradle.gradleHomeDir.canonicalPath,
         dependencyCache: BuildReceipt.treeHash(new File(userHome, 'caches/modules-2/files-2.1')),
         gradleLibraries: BuildReceipt.treeHash(new File(project.gradle.gradleHomeDir, 'lib')),
         os: System.getProperty('os.name'), arch: System.getProperty('os.arch'),
         toolchains: homes.collect { new File(it).canonicalFile }.unique().sort { it.path }
                 .collect { BuildReceipt.javaIdentity(it) }]
    }
}
