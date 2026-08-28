package com.naocraftlab.skins.core.compatibility;


public enum SkinConflictReason {
    MALFORMED_EARS_DATA(SkinConsumer.EARS),
    MALFORMED_EXPRESSIVE_DATA(SkinConsumer.FRESH_MOVES),
    MISSING_EXPRESSIVE_RUNTIME(SkinConsumer.FRESH_MOVES);

    private final SkinConsumer consumer;

    SkinConflictReason(SkinConsumer consumer) {
        this.consumer = consumer;
    }

    public SkinConsumer consumer() {
        return consumer;
    }

    public boolean affects(SkinConsumer candidate) {
        if (this == MALFORMED_EXPRESSIVE_DATA
                || this == MISSING_EXPRESSIVE_RUNTIME) {
            return candidate == SkinConsumer.FRESH_MOVES
                    || candidate == SkinConsumer.JUST_EXPRESSIONS;
        }
        return consumer == candidate;
    }
}
