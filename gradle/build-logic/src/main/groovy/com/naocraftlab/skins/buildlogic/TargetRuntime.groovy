package com.naocraftlab.skins.buildlogic

final class TargetRuntime {
    static File wrapper(File root, Map catalog, Map target) {
        Map family = catalog.gradleFamilies[target.gradleFamily] as Map
        new File(root, "${family.wrapperPath}/gradlew")
    }

    static String resolveJavaHome(int version) {
        String explicit = System.getenv("NCLSKINS_JAVA${version}")
        if (matchesJava(explicit, version)) return explicit
        String current = System.getProperty('java.home')
        if (matchesJava(current, version)) return current
        if (System.getProperty('os.name').toLowerCase().contains('mac')) {
            Process discover = new ProcessBuilder('/usr/libexec/java_home', '-v', version.toString()).start()
            String found = discover.inputStream.getText('UTF-8').trim()
            if (discover.waitFor() == 0 && matchesJava(found, version)) return found
        }
        File sdkman = new File(System.getProperty('user.home'), '.sdkman/candidates/java')
        List<File> candidates = sdkman.listFiles()?.findAll { it.isDirectory() && it.name.startsWith(version.toString()) }?.sort { a, b -> b.name <=> a.name } ?: []
        File selected = candidates.find { matchesJava(it.absolutePath, version) }
        if (selected != null) return selected.absolutePath
        throw new IllegalStateException("Set NCLSKINS_JAVA${version} to a JDK ${version} home")
    }

    static void configureEnvironment(ProcessBuilder builder, String javaHome) {
        Map<String, String> environment = builder.environment()
        environment.put('JAVA_HOME', javaHome)
        environment.put('PATH', new File(javaHome, 'bin').absolutePath + File.pathSeparator + environment.get('PATH'))
    }

    static boolean matchesJava(String home, int version) {
        if (home == null || home.isBlank()) return false
        File executable = new File(home, 'bin/java')
        if (!executable.canExecute()) return false
        Process process = new ProcessBuilder(executable.absolutePath, '-XshowSettings:properties', '-version').redirectErrorStream(true).start()
        String output = process.inputStream.getText('UTF-8')
        if (process.waitFor() != 0) return false
        def match = output =~ /java\.specification\.version\s*=\s*([^\s]+)/
        match.find() && match.group(1) == version.toString()
    }

    private TargetRuntime() {}
}
