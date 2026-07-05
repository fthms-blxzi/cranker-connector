package scaffolding;

import io.muserver.MuHandler;
import io.muserver.MuHandlerBuilder;
import io.muserver.RouteHandler;
import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import com.hsbc.cranker.mucranker.RustCrankerRouter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.concurrent.TimeUnit;

public class TestServerBuilder {

    private final MuServerBuilder builder;
    private RustCrankerRouter rustRouterForReg = null;
    private RustCrankerRouter rustRouterForVisit = null;

    private TestServerBuilder(MuServerBuilder builder) {
        this.builder = builder;
    }

    public static TestServerBuilder httpServer() {
        return new TestServerBuilder(MuServerBuilder.httpServer());
    }

    public static TestServerBuilder httpsServer() {
        return new TestServerBuilder(MuServerBuilder.httpsServer());
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
        builder.withHttpPort(port);
        return this;
    }

    public TestServerBuilder withHttpsPort(int port) {
        builder.withHttpPort(port);
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
        builder.withMaxHeadersSize(maxHeadersSize);
        return this;
    }

    public MuServer start() {
        boolean isRustMode = Boolean.getBoolean("cranker.router.rust") || "true".equalsIgnoreCase(System.getenv("CRANKER_ROUTER_RUST"));
        if (isRustMode && (rustRouterForReg != null || rustRouterForVisit != null)) {
            // In Rust mode the PortUnifiedProxy speaks plain HTTP to the Java server,
            // so ensure the builder also opens an HTTP port.
            builder.withHttpPort(0);
        }
        MuServer realServer = builder.start();
        if (isRustMode && (rustRouterForReg != null || rustRouterForVisit != null)) {
            try {
                int regPort = rustRouterForReg != null ? rustRouterForReg.getRegPort() : rustRouterForVisit.getVisitPort();
                int visitPort = rustRouterForVisit != null ? rustRouterForVisit.getVisitPort() : rustRouterForReg.getRegPort();
                int realServerPort = realServer.httpUri() != null ? realServer.httpUri().getPort() : realServer.uri().getPort();
                PortUnifiedProxy proxy = new PortUnifiedProxy(realServerPort, regPort, visitPort);
                return (MuServer) Proxy.newProxyInstance(
                        MuServer.class.getClassLoader(),
                        new Class<?>[]{MuServer.class},
                        new InvocationHandler() {
                            @Override
                            public Object invoke(Object proxyInstance, Method method, Object[] args) throws Throwable {
                                String methodName = method.getName();
                                if (methodName.equals("uri") || methodName.equals("httpUri") || methodName.equals("httpsUri")) {
                                    return URI.create("http://127.0.0.1:" + proxy.getPort());
                                } else if (methodName.equals("stop")) {
                                    proxy.stop();
                                    return method.invoke(realServer, args);
                                }
                                return method.invoke(realServer, args);
                            }
                        }
                );
            } catch (Exception e) {
                throw new RuntimeException("Failed to start unified port proxy", e);
            }
        }
        return realServer;
    }

    private static class PortUnifiedProxy {
        private final ServerSocket serverSocket;
        private final int realServerPort;
        private final int regPort;
        private final int visitPort;
        private final Thread thread;
        private volatile boolean running = true;
        private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();

        public PortUnifiedProxy(int realServerPort, int regPort, int visitPort) throws Exception {
            this.serverSocket = new ServerSocket(0);
            this.realServerPort = realServerPort;
            this.regPort = regPort;
            this.visitPort = visitPort;
            this.thread = new Thread(this::run);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        public int getPort() {
            return serverSocket.getLocalPort();
        }

        public void stop() {
            running = false;
            try { serverSocket.close(); } catch (Exception ignored) {}
            for (Socket s : activeSockets) {
                try { s.close(); } catch (Exception ignored) {}
            }
            activeSockets.clear();
        }

        private void run() {
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    activeSockets.add(clientSocket);
                    new Thread(() -> handle(clientSocket)).start();
                } catch (Exception e) {
                    // ignore
                }
            }
        }

        private void handle(Socket clientSocket) {
            try {
                clientSocket.setTcpNoDelay(true);
                InputStream in = clientSocket.getInputStream();
                byte[] buffer = new byte[8192];
                int read = in.read(buffer);
                if (read <= 0) {
                    activeSockets.remove(clientSocket);
                    clientSocket.close();
                    return;
                }
                String headers = new String(buffer, 0, read, java.nio.charset.StandardCharsets.US_ASCII);
                int targetPort = visitPort;
                if (headers.contains("/register") || headers.contains("/deregister") || headers.contains("/dark-mode")) {
                    targetPort = regPort;
                } else if (headers.contains("/health")) {
                    targetPort = realServerPort;
                }
                System.out.println("DEBUG PROXY: targetPort=" + targetPort + " for " + headers.substring(0, Math.min(100, headers.length())).replace("\r", "\\r").replace("\n", "\\n"));
                Socket targetSocket = new Socket("127.0.0.1", targetPort);
                activeSockets.add(targetSocket);
                targetSocket.setTcpNoDelay(true);
                OutputStream out = targetSocket.getOutputStream();
                out.write(buffer, 0, read);
                out.flush();

                Thread t1 = new Thread(() -> copy(clientSocket, targetSocket));
                Thread t2 = new Thread(() -> copy(targetSocket, clientSocket));
                t1.start();
                t2.start();
            } catch (Exception e) {
                activeSockets.remove(clientSocket);
                try { clientSocket.close(); } catch (Exception ignored) {}
            }
        }

        private void copy(Socket source, Socket dest) {
            try (InputStream in = source.getInputStream();
                 OutputStream out = dest.getOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (Exception ignored) {
            } finally {
                activeSockets.remove(source);
                activeSockets.remove(dest);
                try { source.close(); } catch (Exception ignored) {}
                try { dest.close(); } catch (Exception ignored) {}
            }
        }
    }
}
