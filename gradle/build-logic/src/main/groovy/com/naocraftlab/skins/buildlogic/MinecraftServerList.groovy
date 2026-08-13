package com.naocraftlab.skins.buildlogic

import org.gradle.api.GradleException

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

final class MinecraftServerList {
    static final byte END = 0
    static final byte BYTE = 1
    static final byte SHORT = 2
    static final byte INT = 3
    static final byte LONG = 4
    static final byte FLOAT = 5
    static final byte DOUBLE = 6
    static final byte BYTE_ARRAY = 7
    static final byte STRING = 8
    static final byte LIST = 9
    static final byte COMPOUND = 10
    static final byte INT_ARRAY = 11
    static final byte LONG_ARRAY = 12

    static void merge(Path file, List<Map<String, String>> managedEntries) {
        merge(file, managedEntries, managedEntries*.name as Set)
    }

    static void merge(
            Path file,
            List<Map<String, String>> managedEntries,
            Set<String> allManagedNames) {
        NbtTag root = Files.isRegularFile(file) ? read(file) : emptyRoot()
        if (root.type != COMPOUND || !(root.value instanceof Map)) {
            throw new GradleException("Minecraft server list root is not a compound: ${file}")
        }
        Map<String, NbtTag> rootCompound = root.value as Map<String, NbtTag>
        NbtTag servers = rootCompound['servers']
        if (servers == null) {
            servers = new NbtTag(LIST, new NbtList(COMPOUND, []))
            rootCompound['servers'] = servers
        }
        if (servers.type != LIST || !(servers.value instanceof NbtList) ||
                (servers.value as NbtList).elementType != COMPOUND) {
            throw new GradleException("Minecraft server list has an invalid servers tag: ${file}")
        }
        List<NbtTag> existing = new ArrayList<>((servers.value as NbtList).values)
        Map<String, NbtTag> existingByName = new LinkedHashMap<>()
        existing.each { NbtTag tag ->
            String name = stringValue(tag, 'name')
            if (name != null && !existingByName.containsKey(name)) existingByName[name] = tag
        }
        Set<String> managedNames = managedEntries.collect { it.name } as Set
        if (!allManagedNames.containsAll(managedNames)) {
            throw new GradleException('Managed server-name universe omits desired entries')
        }
        List<NbtTag> merged = []
        managedEntries.each { Map<String, String> entry ->
            NbtTag tag = existingByName[entry.name] ?: serverEntry(entry.name, entry.ip)
            Map<String, NbtTag> compound = tag.value as Map<String, NbtTag>
            compound['name'] = new NbtTag(STRING, entry.name)
            compound['ip'] = new NbtTag(STRING, entry.ip)
            if (!compound.containsKey('hidden')) compound['hidden'] = new NbtTag(BYTE, (byte) 0)
            merged.add(tag)
        }
        existing.each { NbtTag tag ->
            String name = stringValue(tag, 'name')
            if (name == null || !allManagedNames.contains(name)) merged.add(tag)
        }
        (servers.value as NbtList).values = merged
        writeAtomic(file, root)
    }

    static List<Map<String, String>> entries(Path file) {
        NbtTag root = read(file)
        NbtTag servers = (root.value as Map<String, NbtTag>)['servers']
        if (servers?.type != LIST || (servers.value as NbtList).elementType != COMPOUND) return []
        (servers.value as NbtList).values.collect { NbtTag tag ->
            [name: stringValue(tag, 'name'), ip: stringValue(tag, 'ip')]
        }
    }

    static NbtTag read(Path file) {
        file.withInputStream { InputStream raw ->
            DataInputStream input = new DataInputStream(new BufferedInputStream(raw))
            byte type = input.readByte()
            if (type == END) throw new GradleException("NBT root cannot be END: ${file}")
            readString(input)
            new NbtTag(type, readPayload(input, type))
        }
    }

    static void writeAtomic(Path file, NbtTag root) {
        Files.createDirectories(file.parent)
        Path temporary = file.resolveSibling(file.fileName.toString() + '.tmp')
        temporary.withOutputStream { OutputStream raw ->
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(raw))
            output.writeByte(root.type)
            writeString(output, '')
            writePayload(output, root)
            output.flush()
        }
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private static NbtTag emptyRoot() {
        new NbtTag(COMPOUND, new LinkedHashMap<String, NbtTag>())
    }

    private static NbtTag serverEntry(String name, String ip) {
        Map<String, NbtTag> compound = new LinkedHashMap<>()
        compound['hidden'] = new NbtTag(BYTE, (byte) 0)
        compound['ip'] = new NbtTag(STRING, ip)
        compound['name'] = new NbtTag(STRING, name)
        new NbtTag(COMPOUND, compound)
    }

    private static String stringValue(NbtTag compoundTag, String key) {
        if (compoundTag?.type != COMPOUND || !(compoundTag.value instanceof Map)) return null
        NbtTag value = (compoundTag.value as Map<String, NbtTag>)[key]
        value?.type == STRING ? value.value?.toString() : null
    }

    private static Object readPayload(DataInputStream input, byte type) {
        switch (type) {
            case BYTE: return input.readByte()
            case SHORT: return input.readShort()
            case INT: return input.readInt()
            case LONG: return input.readLong()
            case FLOAT: return input.readFloat()
            case DOUBLE: return input.readDouble()
            case BYTE_ARRAY:
                int byteLength = requireLength(input.readInt())
                byte[] bytes = new byte[byteLength]
                input.readFully(bytes)
                return bytes
            case STRING: return readString(input)
            case LIST:
                byte elementType = input.readByte()
                int listLength = requireLength(input.readInt())
                List<NbtTag> values = new ArrayList<>(listLength)
                listLength.times { values.add(new NbtTag(elementType, readPayload(input, elementType))) }
                return new NbtList(elementType, values)
            case COMPOUND:
                Map<String, NbtTag> compound = new LinkedHashMap<>()
                while (true) {
                    byte childType = input.readByte()
                    if (childType == END) break
                    String name = readString(input)
                    compound[name] = new NbtTag(childType, readPayload(input, childType))
                }
                return compound
            case INT_ARRAY:
                int intLength = requireLength(input.readInt())
                int[] ints = new int[intLength]
                for (int index = 0; index < intLength; index++) ints[index] = input.readInt()
                return ints
            case LONG_ARRAY:
                int longLength = requireLength(input.readInt())
                long[] longs = new long[longLength]
                for (int index = 0; index < longLength; index++) longs[index] = input.readLong()
                return longs
            default: throw new GradleException("Unsupported NBT tag type ${type}")
        }
    }

    private static void writePayload(DataOutputStream output, NbtTag tag) {
        switch (tag.type) {
            case BYTE: output.writeByte((tag.value as Number).byteValue()); return
            case SHORT: output.writeShort((tag.value as Number).shortValue()); return
            case INT: output.writeInt((tag.value as Number).intValue()); return
            case LONG: output.writeLong((tag.value as Number).longValue()); return
            case FLOAT: output.writeFloat((tag.value as Number).floatValue()); return
            case DOUBLE: output.writeDouble((tag.value as Number).doubleValue()); return
            case BYTE_ARRAY:
                byte[] bytes = tag.value as byte[]
                output.writeInt(bytes.length); output.write(bytes); return
            case STRING: writeString(output, tag.value.toString()); return
            case LIST:
                NbtList list = tag.value as NbtList
                output.writeByte(list.elementType)
                output.writeInt(list.values.size())
                list.values.each { writePayload(output, it) }
                return
            case COMPOUND:
                (tag.value as Map<String, NbtTag>).each { String name, NbtTag child ->
                    output.writeByte(child.type)
                    writeString(output, name)
                    writePayload(output, child)
                }
                output.writeByte(END)
                return
            case INT_ARRAY:
                int[] ints = tag.value as int[]
                output.writeInt(ints.length); ints.each { output.writeInt(it) }; return
            case LONG_ARRAY:
                long[] longs = tag.value as long[]
                output.writeInt(longs.length); longs.each { output.writeLong(it) }; return
            default: throw new GradleException("Unsupported NBT tag type ${tag.type}")
        }
    }

    private static String readString(DataInputStream input) {
        int length = input.readUnsignedShort()
        byte[] bytes = new byte[length]
        input.readFully(bytes)
        new String(bytes, StandardCharsets.UTF_8)
    }

    private static void writeString(DataOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8)
        if (bytes.length > 65_535) throw new GradleException('NBT string exceeds 65535 bytes')
        output.writeShort(bytes.length)
        output.write(bytes)
    }

    private static int requireLength(int value) {
        if (value < 0 || value > 16_777_216) throw new GradleException("Invalid NBT collection length ${value}")
        value
    }

    static final class NbtTag {
        final byte type
        final Object value

        NbtTag(byte type, Object value) {
            this.type = type
            this.value = value
        }
    }

    static final class NbtList {
        final byte elementType
        List<NbtTag> values

        NbtList(byte elementType, List<NbtTag> values) {
            this.elementType = elementType
            this.values = values
        }
    }

    private MinecraftServerList() {}
}
