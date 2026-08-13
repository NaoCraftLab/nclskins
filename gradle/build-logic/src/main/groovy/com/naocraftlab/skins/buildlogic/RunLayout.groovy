package com.naocraftlab.skins.buildlogic

final class RunLayout {
    static final List<String> RUN_KINDS = ['Client', 'LicensedClient', 'Server'].asImmutable()
    static final Map<String, String> RUN_DIRECTORIES = [
            Client: 'client',
            LicensedClient: 'client-licensed',
            Server: 'server'
    ].asImmutable()

    static File modDirectory(File repository, Map target, String runKind) {
        modDirectory(repository, target, target.minecraft.version.toString(), runKind)
    }

    static File modDirectory(
            File repository, Map target, String minecraftVersion, String runKind) {
        String directory = RUN_DIRECTORIES[runKind]
        if (directory == null) throw new IllegalArgumentException("Unsupported run kind ${runKind}")
        new File(repository,
                "runs/${segment(minecraftVersion)}/${segment(target.loader.id)}/${directory}")
    }

    static File topologyDirectory(File repository, Map topology) {
        String mode = topology.mode.toString()
        String target = mode == 'standalone'
                ? segment(topology.kernel)
                : "${segment(mode)}-${segment(topology.kernel)}".toString()
        new File(repository, "runs/${segment(topology.minecraft)}/${target}")
    }

    static File topologyTrashDirectory(File repository, Map topology, long timestamp) {
        new File(repository,
                "runs/${segment(topology.minecraft)}/.trash/${topologyDirectory(repository, topology).name}-${timestamp}")
    }

    static File legacyModDirectory(File repository, Map target) {
        new File(repository, "${target.path}/run")
    }

    static File legacyTopologyDirectory(File repository, Map topology) {
        new File(repository, "runs/server-plugins/${segment(topology.id)}")
    }

    static String segment(Object value) {
        String text = value?.toString()
        if (text == null || text.isBlank() || !(text ==~ /[A-Za-z0-9][A-Za-z0-9._-]*/)) {
            throw new IllegalArgumentException("Unsafe run path segment: ${value}")
        }
        text
    }

    private RunLayout() {}
}
