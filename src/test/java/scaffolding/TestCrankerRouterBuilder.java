package scaffolding;

import com.hsbc.cranker.mucranker.*;
import io.muserver.MuRequest;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class TestCrankerRouterBuilder {
    private final CrankerRouterBuilder delegate = CrankerRouterBuilder.crankerRouter();
    private boolean http2 = true;

    public static TestCrankerRouterBuilder crankerRouter() {
        return new TestCrankerRouterBuilder();
    }

    public TestCrankerRouterBuilder withHttp2(boolean http2) {
        this.http2 = http2;
        return this;
    }

    public TestCrankerRouterBuilder withDiscardClientForwardedHeaders(boolean discardClientForwardedHeaders) {
        delegate.withDiscardClientForwardedHeaders(discardClientForwardedHeaders);
        return this;
    }

    public TestCrankerRouterBuilder withSendLegacyForwardedHeaders(boolean sendLegacyForwardedHeaders) {
        delegate.withSendLegacyForwardedHeaders(sendLegacyForwardedHeaders);
        return this;
    }

    public TestCrankerRouterBuilder withViaName(String viaName) {
        delegate.withViaName(viaName);
        return this;
    }

    public TestCrankerRouterBuilder withIdleTimeout(long duration, TimeUnit unit) {
        delegate.withIdleTimeout(duration, unit);
        return this;
    }

    public TestCrankerRouterBuilder withRoutesKeepTime(long duration, TimeUnit unit) {
        delegate.withRoutesKeepTime(duration, unit);
        return this;
    }

    public TestCrankerRouterBuilder withPingSentAfterNoWritesFor(int duration, TimeUnit unit) {
        delegate.withPingSentAfterNoWritesFor(duration, unit);
        return this;
    }

    public TestCrankerRouterBuilder withConnectorMaxWaitInMillis(long maxWaitInMillis) {
        delegate.withConnectorMaxWaitInMillis(maxWaitInMillis);
        return this;
    }

    public TestCrankerRouterBuilder proxyHostHeader(boolean sendHostToTarget) {
        delegate.proxyHostHeader(sendHostToTarget);
        return this;
    }

    public TestCrankerRouterBuilder withRegistrationIpValidator(IPValidator ipValidator) {
        delegate.withRegistrationIpValidator(ipValidator);
        return this;
    }

    public TestCrankerRouterBuilder withProxyListeners(List<ProxyListener> proxyListeners) {
        delegate.withProxyListeners(proxyListeners);
        return this;
    }

    public TestCrankerRouterBuilder withRouteResolver(RouteResolver routeResolver) {
        delegate.withRouteResolver(routeResolver);
        return this;
    }

    public TestCrankerRouterBuilder withSupportedCrankerProtocols(List<String> protocols) {
        delegate.withSupportedCrankerProtocols(protocols);
        return this;
    }

    public TestCrankerRouterBuilder withClientIpProvider(Function<MuRequest, String> clientIpProvider) {
        delegate.withClientIpProvider(clientIpProvider);
        return this;
    }

    @SuppressWarnings("unchecked")
    public CrankerRouter start() {
        if (RustTestHelper.isRustMode()) {
            try {
                var ipValidatorField = CrankerRouterBuilder.class.getDeclaredField("ipValidator");
                ipValidatorField.setAccessible(true);
                IPValidator ipValidator = (IPValidator) ipValidatorField.get(delegate);

                var discardClientForwardedHeadersField = CrankerRouterBuilder.class.getDeclaredField("discardClientForwardedHeaders");
                discardClientForwardedHeadersField.setAccessible(true);
                boolean discardClientForwardedHeaders = (boolean) discardClientForwardedHeadersField.get(delegate);

                var sendLegacyForwardedHeadersField = CrankerRouterBuilder.class.getDeclaredField("sendLegacyForwardedHeaders");
                sendLegacyForwardedHeadersField.setAccessible(true);
                boolean sendLegacyForwardedHeaders = (boolean) sendLegacyForwardedHeadersField.get(delegate);

                var viaValueField = CrankerRouterBuilder.class.getDeclaredField("viaValue");
                viaValueField.setAccessible(true);
                String viaValue = (String) viaValueField.get(delegate);

                var doNotProxyHeadersField = CrankerRouterBuilder.class.getDeclaredField("doNotProxyHeaders");
                doNotProxyHeadersField.setAccessible(true);
                Set<String> doNotProxyHeaders = (Set<String>) doNotProxyHeadersField.get(delegate);

                var maxWaitInMillisField = CrankerRouterBuilder.class.getDeclaredField("maxWaitInMillis");
                maxWaitInMillisField.setAccessible(true);
                long maxWaitInMillis = (long) maxWaitInMillisField.get(delegate);

                var pingAfterWriteMillisField = CrankerRouterBuilder.class.getDeclaredField("pingAfterWriteMillis");
                pingAfterWriteMillisField.setAccessible(true);
                long pingAfterWriteMillis = (long) pingAfterWriteMillisField.get(delegate);

                var idleReadTimeoutMillsField = CrankerRouterBuilder.class.getDeclaredField("idleReadTimeoutMills");
                idleReadTimeoutMillsField.setAccessible(true);
                long idleReadTimeoutMills = (long) idleReadTimeoutMillsField.get(delegate);

                var routesKeepTimeMillisField = CrankerRouterBuilder.class.getDeclaredField("routesKeepTimeMillis");
                routesKeepTimeMillisField.setAccessible(true);
                long routesKeepTimeMillis = (long) routesKeepTimeMillisField.get(delegate);

                var completionListenersField = CrankerRouterBuilder.class.getDeclaredField("completionListeners");
                completionListenersField.setAccessible(true);
                List<ProxyListener> completionListeners = (List<ProxyListener>) completionListenersField.get(delegate);

                var routeResolverField = CrankerRouterBuilder.class.getDeclaredField("routeResolver");
                routeResolverField.setAccessible(true);
                RouteResolver routeResolver = (RouteResolver) routeResolverField.get(delegate);

                var supportedCrankerProtocolField = CrankerRouterBuilder.class.getDeclaredField("supportedCrankerProtocol");
                supportedCrankerProtocolField.setAccessible(true);
                List<String> supportedCrankerProtocol = (List<String>) supportedCrankerProtocolField.get(delegate);

                var clientIpProviderField = CrankerRouterBuilder.class.getDeclaredField("clientIpProvider");
                clientIpProviderField.setAccessible(true);
                Function<MuRequest, String> clientIpProvider = (Function<MuRequest, String>) clientIpProviderField.get(delegate);

                return new com.hsbc.cranker.mucranker.RustCrankerRouter(
                    ipValidator, discardClientForwardedHeaders, sendLegacyForwardedHeaders, viaValue, doNotProxyHeaders,
                    maxWaitInMillis, pingAfterWriteMillis, idleReadTimeoutMills, routesKeepTimeMillis,
                    completionListeners, routeResolver, supportedCrankerProtocol, clientIpProvider,
                    this.http2
                );
            } catch (Exception e) {
                throw new RuntimeException("Failed to construct RustCrankerRouter", e);
            }
        }
        return delegate.start();
    }
}
