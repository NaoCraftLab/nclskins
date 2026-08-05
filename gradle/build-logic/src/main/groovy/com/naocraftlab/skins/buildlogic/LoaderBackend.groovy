package com.naocraftlab.skins.buildlogic

abstract class LoaderBackend {
    private static final Map<String, LoaderBackend> REGISTERED = [
            fabric  : new LoaderBackend('fabric') {
                @Override
                Map<String, String> metadata(Map catalog, Map target, String version) {
                    ['fabric.mod.json': MetadataRenderer.fabric(catalog, target, version)]
                }

                @Override
                Set<String> metadataKeys() {
                    ['files', 'entrypoint', 'serverEntrypoint', 'accessWidener',
                     'packFormat', 'mixins', 'serverMixins'] as Set
                }

                @Override
                String minecraftPredicate(String version) { ">=${version}" }

                @Override
                String accessRelativePath() { 'nclskins.accesswidener' }

                @Override
                String clientCompileTask(Map target) {
                    target.sourceLayout == 'fabricSplit' ? 'compileClientJava' : 'compileJava'
                }

                @Override
                Optional<String> serverPortProperty(int port) { Optional.empty() }
            },
            forge   : new LoaderBackend('forge') {
                @Override
                Map<String, String> metadata(Map catalog, Map target, String version) {
                    ['META-INF/mods.toml': MetadataRenderer.forge(catalog, target, version),
                     'pack.mcmeta'       : MetadataRenderer.rootPack(target)]
                }

                @Override
                Set<String> metadataKeys() { forgeLikeMetadataKeys() }

                @Override
                String minecraftPredicate(String version) { "[${version},)" }

                @Override
                String accessRelativePath() { 'META-INF/accesstransformer.cfg' }

                @Override
                String clientCompileTask(Map target) { 'compileJava' }

                @Override
                Optional<String> serverPortProperty(int port) { Optional.empty() }
            },
            neoforge: new LoaderBackend('neoforge') {
                @Override
                Map<String, String> metadata(Map catalog, Map target, String version) {
                    ['META-INF/neoforge.mods.toml': MetadataRenderer.neoforge(catalog, target, version)]
                }

                @Override
                Set<String> metadataKeys() { forgeLikeMetadataKeys() }

                @Override
                String minecraftPredicate(String version) { "[${version},)" }

                @Override
                String accessRelativePath() { 'META-INF/accesstransformer.cfg' }

                @Override
                String clientCompileTask(Map target) { 'compileJava' }

                @Override
                Optional<String> serverPortProperty(int port) {
                    Optional.of("-PnclskinsServerPort=${port}".toString())
                }
            }
    ].asImmutable()

    final String id

    protected LoaderBackend(String id) { this.id = id }

    abstract Map<String, String> metadata(Map catalog, Map target, String version)

    abstract Set<String> metadataKeys()

    abstract String minecraftPredicate(String version)

    abstract String accessRelativePath()

    abstract String clientCompileTask(Map target)

    abstract Optional<String> serverPortProperty(int port)

    static LoaderBackend require(String id) {
        LoaderBackend backend = REGISTERED[id]
        if (backend == null) throw new IllegalArgumentException("unsupported loader backend: ${id}")
        backend
    }

    static Set<String> ids() { REGISTERED.keySet() }

    private static Set<String> forgeLikeMetadataKeys() {
        ['files', 'modLoader', 'loaderVersion', 'entrypointClass', 'clientEntrypointClass',
         'accessTransformer', 'packFormat', 'mixins', 'serverMixins'] as Set
    }
}
