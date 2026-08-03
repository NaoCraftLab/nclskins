package com.naocraftlab.skins.buildlogic

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

abstract class TargetRunLock implements BuildService<BuildServiceParameters.None>, AutoCloseable {
    @Override
    void close() {}
}
