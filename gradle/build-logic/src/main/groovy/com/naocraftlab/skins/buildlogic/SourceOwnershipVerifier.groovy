package com.naocraftlab.skins.buildlogic

import java.util.regex.Pattern

final class SourceOwnershipVerifier {
    private static final Pattern PACKAGE = Pattern.compile('(?m)^\\s*package\\s+([\\w.]+)')

    static List<String> verify(String path, String source) {
        String declarations = source.replaceAll('(?s)/\\*.*?\\*/', '').replaceAll('(?m)//[^\\r\\n]*', '')
        def packages = PACKAGE.matcher(declarations)
        List<String> errors = []
        while (packages.find()) {
            String name = packages.group(1)
            if (name != 'com.naocraftlab.skins' && !name.startsWith('com.naocraftlab.skins.')) {
                errors.add("${path}: foreign source package ${name}; use an NCL-owned test double or external dependency")
            }
        }
        errors
    }
}
