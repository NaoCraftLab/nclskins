package com.naocraftlab.skins.buildlogic

final class NclReleaseVersion implements Comparable<NclReleaseVersion> {
    final String value
    private final BigInteger major
    private final BigInteger minor
    private final BigInteger patch
    private final int channelRank
    private final BigInteger channelNumber

    static NclReleaseVersion parse(String value) {
        if (value == null || !CatalogTools.VERSION_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("unsupported release version: ${value}")
        }
        List<String> coreAndPre = value.split('-', 2) as List<String>
        List<BigInteger> core = coreAndPre[0].split('\\.').collect { new BigInteger(it) }
        int rank = 2
        BigInteger number = BigInteger.ZERO
        if (coreAndPre.size() == 2) {
            List<String> pre = coreAndPre[1].split('\\.') as List<String>
            rank = pre[0] == 'alpha' ? 0 : 1
            number = new BigInteger(pre[1])
        }
        new NclReleaseVersion(value, core[0], core[1], core[2], rank, number)
    }

    String channel() {
        channelRank == 0 ? 'alpha' : channelRank == 1 ? 'beta' : 'release'
    }

    @Override
    int compareTo(NclReleaseVersion other) {
        int compared = major <=> other.major
        if (compared != 0) return compared
        compared = minor <=> other.minor
        if (compared != 0) return compared
        compared = patch <=> other.patch
        if (compared != 0) return compared
        compared = channelRank <=> other.channelRank
        if (compared != 0) return compared
        channelNumber <=> other.channelNumber
    }

    private NclReleaseVersion(
            String value,
            BigInteger major,
            BigInteger minor,
            BigInteger patch,
            int channelRank,
            BigInteger channelNumber) {
        this.value = value
        this.major = major
        this.minor = minor
        this.patch = patch
        this.channelRank = channelRank
        this.channelNumber = channelNumber
    }
}
