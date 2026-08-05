package com.naocraftlab.skins.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class GenerateTargetBindingsTask extends DefaultTask {
    private static final Set<String> SERVER_ROLES = [
            'serverCommand',
            'serverProfileVerification',
            'serverProfileMutation',
            'serverTracking',
            'serverPlayerInfoPublication',
            'serverLoader'
    ] as Set

    @InputFile
    abstract RegularFileProperty getCatalogFile()

    @Input
    abstract Property<String> getTargetId()

    @OutputDirectory
    abstract DirectoryProperty getClientOutputDirectory()

    @OutputDirectory
    abstract DirectoryProperty getServerOutputDirectory()

    @TaskAction
    void generate() {
        Map catalog = CatalogTools.loadJson(catalogFile.get().asFile.toPath())
        Map target = CatalogTools.selectTarget(catalog, targetId.get())
        Map bindingTarget = new LinkedHashMap(target)
        bindingTarget.__clientProviderClass =
                catalog.profiles.epochs[target.epochProfile].clientProviderClass
        Map capabilities = target.capabilities as Map
        writeBinding(
                clientOutputDirectory.get().asFile,
                'TargetClientBindings',
                capabilities.findAll { Object role, Object ignored -> !SERVER_ROLES.contains(role.toString()) },
                bindingTarget)
        writeBinding(
                serverOutputDirectory.get().asFile,
                'TargetServerBindings',
                capabilities.findAll { Object role, Object ignored -> SERVER_ROLES.contains(role.toString()) },
                bindingTarget)
    }

    private static void writeBinding(
            File root,
            String className,
            Map capabilities,
            Map target) {
        File destination = new File(
                root,
                "com/naocraftlab/skins/generated/${className}.java")
        destination.parentFile.mkdirs()
        List<String> entries = capabilities.collect { Object role, Object implementation ->
            "            Map.entry(\"${escape(role)}\", \"${escape(implementation)}\")"
        }
        String providerMethod = className == 'TargetClientBindings'
                ? """
    public static com.naocraftlab.skins.runtime.ClientCapabilityProvider.Provision provision() {
        return new ${targetClientProvider(target)}().provision();
    }
"""
                : ''
        destination.setText("""package com.naocraftlab.skins.generated;

import java.util.Map;

/** Generated from gradle/targets.json. Do not edit. */
public final class ${className} {
    public static final String TARGET_ID = \"${escape(target.id)}\";
    public static final String EPOCH_PROFILE = \"${escape(target.epochProfile)}\";
    public static final String LOADER_PROFILE = \"${escape(target.loaderProfile)}\";
    public static final String INTEGRATION_PROFILE = \"${escape(target.integrationProfile)}\";
    public static final String BUILD_PROFILE = \"${escape(target.buildProfile)}\";
    public static final Map<String, String> CAPABILITIES = Map.ofEntries(
${entries.join(',\n')});
${providerMethod}

    private ${className}() {}
}
""", 'UTF-8')
    }

    private static String targetClientProvider(Map target) {
        target.__clientProviderClass?.toString() ?: target.clientProviderClass?.toString()
    }

    private static String escape(Object value) {
        value.toString().replace('\\', '\\\\').replace('"', '\\"')
    }
}
