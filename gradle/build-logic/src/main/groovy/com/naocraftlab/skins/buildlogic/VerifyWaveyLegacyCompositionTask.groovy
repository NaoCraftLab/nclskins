package com.naocraftlab.skins.buildlogic

import org.gradle.api.provider.MapProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Input

abstract class VerifyWaveyLegacyCompositionTask extends VerifyWaveyCapesCompositionTask {
    VerifyWaveyLegacyCompositionTask() { providerEnvironment.convention([:]) }
    @Input abstract MapProperty<String, String> getProviderEnvironment()
    @Optional @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getGeneratedMixinRefmap()
    @Optional @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getNamedWaveyRuntime()

    @Override protected Process startCompositionProcess(List<String> argv, File work, File log) {
        Map<String, String> environment = providerEnvironment.get()
        if (!environment.keySet().every { it in ['MOD_CLASSES', 'MCP_MAPPINGS'] })
            throw new IllegalArgumentException('Unsafe legacy provider environment')
        def builder = new ProcessBuilder(argv).directory(work).redirectErrorStream(true).redirectOutput(log)
        builder.environment().remove('MOD_CLASSES')
        builder.environment().putAll(environment)
        builder.start()
    }
}
