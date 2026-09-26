package com.naocraftlab.skins.runtime;

import com.naocraftlab.skins.client.GameSessionTokenSource;
import com.naocraftlab.skins.client.GameSessionIdentityChangedException;
import java.util.Objects;

final class AccountSessionAdapter {
    @FunctionalInterface
    interface ScopedTokenRequest<T> {
        T execute(GameSessionTokenSource scopedTokens);
    }

    static final class ScopedCheckedFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ScopedCheckedFailure(Exception cause) {
            super(cause);
        }
    }

    static final class ScopedCallbackRuntimeFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final RuntimeException original;

        ScopedCallbackRuntimeFailure(RuntimeException original) {
            super(null, null, false, false);
            this.original = Objects.requireNonNull(original, "original");
        }

        RuntimeException original() {
            return original;
        }
    }

    static final class PinnedTokenSource implements GameSessionTokenSource {
        private final GameSessionTokenSource delegate;
        private final SessionIdentity identity;

        PinnedTokenSource(GameSessionTokenSource delegate, SessionIdentity identity) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.identity = Objects.requireNonNull(identity, "identity");
        }

        @Override
        public SessionIdentity currentSession() {
            return identity;
        }

        @Override
        public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
            Objects.requireNonNull(request, "request");
            return delegate.withSession((current, accessToken) -> {
                if (!identity.profileId().equals(current.profileId())) {
                    throw new GameSessionIdentityChangedException();
                }
                return request.execute(accessToken);
            });
        }

        <T> T withRequestToken(ScopedTokenRequest<T> request) {
            Objects.requireNonNull(request, "request");
            return delegate.withSession((current, accessToken) -> {
                if (!identity.profileId().equals(current.profileId())) {
                    throw new GameSessionIdentityChangedException();
                }
                GameSessionTokenSource scoped = new RequestScopedTokenSource(
                        identity, accessToken);
                try {
                    return request.execute(scoped);
                } catch (ScopedCheckedFailure checkedFailure) {
                    throw checkedFailure;
                } catch (RuntimeException callbackFailure) {
                    throw new ScopedCallbackRuntimeFailure(callbackFailure);
                }
            });
        }
    }

    static final class RequestScopedTokenSource implements GameSessionTokenSource {
        private final SessionIdentity identity;
        private final String accessToken;

        private RequestScopedTokenSource(SessionIdentity identity, String accessToken) {
            this.identity = Objects.requireNonNull(identity, "identity");
            this.accessToken = Objects.requireNonNull(accessToken, "accessToken");
        }

        @Override
        public SessionIdentity currentSession() {
            return identity;
        }

        @Override
        public <T, E extends Exception> T withAccessToken(TokenRequest<T, E> request) throws E {
            return Objects.requireNonNull(request, "request").execute(accessToken);
        }
    }

}
