package com.naocraftlab.skins.buildlogic

import groovy.json.JsonSlurper

import javax.imageio.ImageIO
import java.security.MessageDigest
import java.util.zip.ZipFile

final class DefaultSkinParityVerifier {
    static void verify(File javap, String classpath, Collection<File> resourceFiles) {
        def result = AbiVerifier.run([javap.absolutePath, '-c', '-p', '-classpath', classpath,
                'net.minecraft.client.resources.DefaultPlayerSkin'])
        if (result.exit != 0) throw new IllegalStateException('Cannot inspect native default skin selector')
        verifySelector(result.output)
        Map expected = new JsonSlurper().parse(DefaultSkinParityVerifier.getResourceAsStream('default-skin-pixels.json')) as Map
        expected.each { String path, Object hash ->
            byte[] bytes = resource(resourceFiles, path)
            if (bytes == null) throw new IllegalStateException("Native default skin resource unavailable: ${path}")
            if (pixelHash(bytes) != hash) {
                throw new IllegalStateException("Native default skin pixels changed: ${path}")
            }
        }
    }

    static void verifySelector(String bytecode) {
        List<String> names = ['alex', 'ari', 'efe', 'kai', 'makena', 'noor', 'steve', 'sunny', 'zuri']
        List<String> expected = ['slim', 'wide'].collectMany { model -> names.collect { "${model}/${it}".toString() } }
        List<String> textures = (bytecode =~ /\/\/ String (?:textures\/)?entity\/player\/(slim|wide)\/([a-z]+)(?:\.png)?/)
                .collect { match -> "${match[1]}/${match[2]}".toString() }
        List<String> lines = bytecode.readLines()
        int hashLine = lines.findIndexOf { it.contains('// Method java/util/UUID.hashCode:()I') }
        List<String> operations = hashLine < 2 || hashLine + 5 >= lines.size() ? []
                : lines.subList(hashLine - 2, hashLine + 6).collect { line ->
                    def match = line =~ /^\s*\d+:\s+(\w+)/
                    match.find() ? match.group(1) : ''
                }
        List<String> models = (bytecode =~ /\/\/ Field [^\s]+\.(SLIM|WIDE):/).collect { it[1] }
        if (textures != expected
                || models != (Collections.nCopies(9, 'SLIM') + Collections.nCopies(9, 'WIDE'))
                || operations != ['getstatic', 'aload_0', 'invokevirtual', 'getstatic', 'arraylength', 'invokestatic', 'aaload', 'areturn']
                || !lines[hashLine + 3].contains('java/lang/Math.floorMod:(II)I')) {
            throw new IllegalStateException('Native default skin selector differs from the shared UUID contract')
        }
    }

    private static byte[] resource(Collection<File> files, String path) {
        for (File file : files) {
            if (file.isDirectory()) {
                File resource = new File(file, path)
                if (resource.isFile()) return resource.bytes
            } else if (file.isFile() && file.name.endsWith('.jar')) {
                byte[] bytes = new ZipFile(file).withCloseable { zip ->
                    def entry = zip.getEntry(path)
                    entry == null ? null : zip.getInputStream(entry).withCloseable { it.bytes }
                }
                if (bytes != null) return bytes
            }
        }
        return null
    }

    private static String pixelHash(byte[] bytes) {
        def image = ImageIO.read(new ByteArrayInputStream(bytes))
        if (image == null || image.width != 64 || image.height != 64) return ''
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int argb = image.getRGB(x, y)
                if ((argb >>> 24) == 0) argb = 0
                digest.update((byte) (argb >>> 16))
                digest.update((byte) (argb >>> 8))
                digest.update((byte) argb)
                digest.update((byte) (argb >>> 24))
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}
