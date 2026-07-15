package scaffolding;

import com.hsbc.cranker.mucranker.RustCrankerRouter;
import io.muserver.MuHandler;
import io.muserver.MuHandlerBuilder;
import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import io.muserver.RouteHandler;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.concurrent.TimeUnit;

public class TestServerBuilder {

    private final MuServerBuilder builder;
    private final boolean isHttps;
    private RustCrankerRouter rustRouterForReg = null;
    private RustCrankerRouter rustRouterForVisit = null;
    private int maxHeadersSize = -1;

    private int requestedPort = 0;

    private TestServerBuilder(MuServerBuilder builder, boolean isHttps) {
        this.builder = builder;
        this.isHttps = isHttps;
    }

    public static TestServerBuilder httpServer() {
        return new TestServerBuilder(MuServerBuilder.httpServer(), false);
    }

    public static TestServerBuilder httpsServer() {
        return new TestServerBuilder(MuServerBuilder.httpsServer(), true);
    }

    public static TestCrankerRouterBuilder crankerRouter() {
        return TestCrankerRouterBuilder.crankerRouter();
    }

    public TestServerBuilder addHandler(MuHandler handler) {
        if (handler instanceof RustCrankerRouter.RustRegistrationHandler) {
            this.rustRouterForReg = ((RustCrankerRouter.RustRegistrationHandler) handler).router;
        } else if (handler instanceof RustCrankerRouter.RustHttpHandler) {
            this.rustRouterForVisit = ((RustCrankerRouter.RustHttpHandler) handler).router;
        }
        builder.addHandler(handler);
        return this;
    }

    public TestServerBuilder addHandler(MuHandlerBuilder handlerBuilder) {
        builder.addHandler(handlerBuilder);
        return this;
    }

    public TestServerBuilder addHandler(io.muserver.Method method, String path, RouteHandler handler) {
        builder.addHandler(method, path, handler);
        return this;
    }

    public TestServerBuilder withHttpPort(int port) {
        this.requestedPort = port;
        builder.withHttpPort(port);
        return this;
    }

    public TestServerBuilder withHttpsPort(int port) {
        this.requestedPort = port;
        if (RustTestHelper.isRustMode()) {
            builder.withHttpsPort(0);
        } else {
            builder.withHttpsPort(port);
        }
        return this;
    }

    public TestServerBuilder withGzipEnabled(boolean enabled) {
        builder.withGzipEnabled(enabled);
        return this;
    }

    public TestServerBuilder withHttp2Config(io.muserver.Http2ConfigBuilder config) {
        builder.withHttp2Config(config);
        return this;
    }

    public TestServerBuilder withIdleTimeout(long timeout, TimeUnit unit) {
        builder.withIdleTimeout(timeout, unit);
        return this;
    }

    public TestServerBuilder withMaxHeadersSize(int maxHeadersSize) {
        this.maxHeadersSize = maxHeadersSize;
        builder.withMaxHeadersSize(maxHeadersSize);
        return this;
    }

    public MuServer start() {
        boolean isRustMode = RustTestHelper.isRustMode();
        if (isRustMode && (rustRouterForReg != null || rustRouterForVisit != null)) {
            int targetPort = -1;
            if (requestedPort > 0) {
                if (isHttps) {
                    targetPort = requestedPort > 50000 ? requestedPort - 10000 : requestedPort + 10000;
                } else {
                    targetPort = requestedPort;
                }
            }
            if (rustRouterForReg != null) {
                if (targetPort > 0) {
                    rustRouterForReg.setPorts(targetPort);
                }
                rustRouterForReg.startOrGetProcess(isHttps, maxHeadersSize);
            }
            if (rustRouterForVisit != null) {
                if (targetPort > 0) {
                    rustRouterForVisit.setPorts(targetPort);
                }
                rustRouterForVisit.startOrGetProcess(isHttps, maxHeadersSize);
            }
            // In Rust mode we direct traffic directly to the Rust ports
            // without starting any JVM-side TCP proxy
            builder.withHttpPort(0);
            if (isHttps) {
                builder.withHttpsPort(0);
            }
        }
        MuServer realServer = builder.start();
        if (isRustMode && (rustRouterForReg != null || rustRouterForVisit != null)) {
            try {
                int regPort = rustRouterForReg != null ? rustRouterForReg.getRegPort() : rustRouterForVisit.getVisitPort();
                int visitPort = rustRouterForVisit != null ? rustRouterForVisit.getVisitPort() : rustRouterForReg.getRegPort();
                final int targetRustPort;
                if (rustRouterForReg != null && rustRouterForVisit != null) {
                    targetRustPort = regPort;
                } else if (rustRouterForReg != null) {
                    targetRustPort = regPort;
                } else {
                    targetRustPort = visitPort;
                }
                return (MuServer) Proxy.newProxyInstance(
                        MuServer.class.getClassLoader(),
                        new Class<?>[]{MuServer.class},
                        new InvocationHandler() {
                            @Override
                            public Object invoke(Object proxyInstance, Method method, Object[] args) throws Throwable {
                                String methodName = method.getName();
                                if (methodName.equals("uri") || methodName.equals("httpUri") || methodName.equals("httpsUri")) {
                                    if (isHttps) {
                                        if (methodName.equals("httpsUri") || methodName.equals("uri")) {
                                            int tlsPort = targetRustPort > 50000 ? targetRustPort - 10000 : targetRustPort + 10000;
                                            return URI.create("https://127.0.0.1:" + tlsPort);
                                        } else {
                                            return URI.create("http://127.0.0.1:" + targetRustPort);
                                        }
                                    } else {
                                        return URI.create("http://127.0.0.1:" + targetRustPort);
                                    }
                                } else if (methodName.equals("stop")) {
                                    return method.invoke(realServer, args);
                                }
                                return method.invoke(realServer, args);
                            }
                        }
                );
            } catch (Exception e) {
                throw new RuntimeException("Failed to delegate to unified rust router server", e);
            }
        }
        return realServer;
    }
}
