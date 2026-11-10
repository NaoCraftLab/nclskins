package com.naocraftlab.skins.buildlogic

import java.security.MessageDigest

final class TestFiles {
    static String hash(File file) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        file.withInputStream { input ->
            byte[] buffer = new byte[65536]
            int count
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count)
        }
        digest.digest().encodeHex().toString()
    }


    static Map<String, String> treeContents(File directory) {
        Map<String, String> contents = new TreeMap<>()
        List<File> files = []
        if (directory.isDirectory()) directory.eachFileRecurse { if (it.isFile()) files.add(it) }
        files.each { File file ->
            contents[directory.toPath().relativize(file.toPath()).toString()] = hash(file)
        }
        contents
    }

}
