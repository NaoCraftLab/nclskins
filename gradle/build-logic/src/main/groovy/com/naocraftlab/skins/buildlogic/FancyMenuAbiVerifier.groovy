package com.naocraftlab.skins.buildlogic

final class FancyMenuAbiVerifier {
    static final String ACTION = 'de.keksuccino.fancymenu.customization.action.Action'
    static final String REGISTRY = 'de.keksuccino.fancymenu.customization.action.ActionRegistry'

    static void verify(String classpath, File javap) {
        Map cache = [:]
        Map action = AbiVerifier.resolveClass(javap, classpath, ACTION, cache)
        Map registry = AbiVerifier.resolveClass(javap, classpath, REGISTRY, cache)
        if (action.access != 'public' || action.finality != 'abstract'
                || registry.access != 'public') throw new IllegalStateException('FancyMenu class ABI changed')
        require(action, 'constructor', '<init>', '(Ljava/lang/String;)V', 'public', 'n/a')
        Map abstracts = [hasValue: '()Z', execute: '(Ljava/lang/String;)V',
                getDisplayName: '()Lnet/minecraft/network/chat/Component;',
                getDescription: '()Lnet/minecraft/network/chat/Component;',
                getValueDisplayName: '()Lnet/minecraft/network/chat/Component;',
                getValuePreset: '()Ljava/lang/String;']
        abstracts.each { name, descriptor -> require(action, 'method', name, descriptor, 'public abstract', 'abstract') }
        if ((action.members as List).count { it.finality == 'abstract' } != abstracts.size()) {
            throw new IllegalStateException('FancyMenu added an unimplemented abstract method')
        }
        require(action, 'method', 'canRunAsync', '()Z', 'public', 'virtual')
        require(action, 'method', 'getIdentifier', '()Ljava/lang/String;', 'public', 'virtual')
        String descriptor = 'L' + ACTION.replace('.', '/') + ';'
        require(registry, 'method', 'register', '(' + descriptor + ')V', 'public static', 'static')
        require(registry, 'method', 'getAction', '(Ljava/lang/String;)' + descriptor, 'public static', 'static')
    }

    static void verifyMetadata(Collection<File> classpath, Map target, boolean runtimeAvailable) {
        if (!runtimeAvailable) return
        Collection<File> artifacts = classpath.findAll { File file ->
            if (!file.isFile() || !file.name.endsWith('.jar')) return false
            new java.util.zip.ZipFile(file).withCloseable { zip ->
                zip.getEntry(ACTION.replace('.', '/') + '.class') != null
            }
        }
        if (artifacts.size() != 1) throw new IllegalStateException('Expected exactly one FancyMenu API artifact')
        new java.util.zip.ZipFile(artifacts.first()).withCloseable { zip ->
            if (target.loader.id == 'fabric') {
                def entry = zip.getEntry('fabric.mod.json')
                if (entry == null) throw new IllegalStateException('FancyMenu Fabric metadata missing')
                Map metadata = new groovy.json.JsonSlurper().parse(zip.getInputStream(entry)) as Map
                Map requirements = metadata.depends as Map
                requireMinimum(target.loader.version.toString(), requirements.fabricloader?.toString())
                requireMinimum(target.loader.apiVersion.toString(), (requirements['fabric-api'] ?: requirements.fabric)?.toString())
            } else {
                def entry = zip.getEntry(target.loader.id == 'forge' ? 'META-INF/mods.toml' : 'META-INF/neoforge.mods.toml')
                if (entry == null) throw new IllegalStateException('FancyMenu loader metadata missing')
                String metadata = zip.getInputStream(entry).getText('UTF-8')
                String block = metadata.split(/\[\[dependencies\.fancymenu\]\]/).find {
                    it =~ /modId\s*=\s*"${target.loader.id}"/
                }
                def range = block == null ? null : (block =~ /versionRange\s*=\s*"([^"]+)"/)
                if (range == null || !range.find()) throw new IllegalStateException('FancyMenu loader requirement missing')
                requireMinimum(target.loader.version.toString(), range.group(1))
            }
        }
    }

    static void requireMinimum(String installed, String predicate) {
        if (predicate == null) throw new IllegalStateException('FancyMenu dependency minimum missing')
        def match = predicate =~ /^(?:>=|\[)([0-9]+(?:\.[0-9]+)*)(?:[-+][A-Za-z0-9.+-]+)?(?:,.*|\s.*)?$/
        if (!match.matches()) throw new IllegalStateException('Unreviewed FancyMenu dependency predicate: ' + predicate)
        List<Integer> actual = installed.split(/[-+]/)[0].tokenize('.').collect { Integer.parseInt(it) }
        List<Integer> minimum = match.group(1).tokenize('.').collect { Integer.parseInt(it) }
        int comparison = 0
        for (int i = 0; i < Math.max(actual.size(), minimum.size()); i++) {
            comparison = (i < actual.size() ? actual[i] : 0) <=> (i < minimum.size() ? minimum[i] : 0)
            if (comparison != 0) break
        }
        if (comparison < 0) throw new IllegalStateException("FancyMenu requires ${predicate}; catalog selects ${installed}")
    }

    private static void require(Map owner, String kind, String name, String descriptor, String access, String finality) {
        if (!(owner.members as List).any { it.kind == kind && it.name == name && it.descriptor == descriptor
                && it.access == access && it.finality == finality }) {
            throw new IllegalStateException("FancyMenu ABI mismatch: ${owner.name}.${name}${descriptor}")
        }
    }
}
