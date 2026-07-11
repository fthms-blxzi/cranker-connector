package com.hsbc.cranker.mucranker;

import io.muserver.MuHandler;
import scaffolding.RustTestHelper;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class RustCrankerRouter implements CrankerRouter {

    private static int lastAssignedPort = 0;

    private final Process process;
    private final int regPort;
    private final int visitPort;
    private final HttpClient httpClient;

    public RustCrankerRouter(
            IPValidator ipValidator,
            boolean discardClientForwardedHeaders,
            boolean sendLegacyForwardedHeaders,
            String viaValue,
            Set<String> doNotProxyHeaders,
            long maxWaitInMillis,
            long pingAfterWriteMillis,
            long idleReadTimeoutMills,
            long routesKeepTimeMillis,
            List<ProxyListener> completionListeners,
            RouteResolver routeResolver,
            List<String> supportedCrankerProtocol,
            java.util.function.Function<io.muserver.MuRequest, String> clientIpProvider
    ) {
        int portToUse = lastAssignedPort;
        if (portToUse == 0 || !isPortFree(portToUse)) {
            portToUse = findFreePort();
        }
        lastAssignedPort = portToUse;
        this.regPort = portToUse;
        this.visitPort = this.regPort;

        HttpClient client = null;
        try {
            javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                }
            };
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            client = HttpClient.newBuilder()
                    .sslContext(sc)
                    .connectTimeout(java.time.Duration.ofMillis(2000))
                    .build();
        } catch (Exception e) {
            client = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofMillis(2000))
                    .build();
        }
        this.httpClient = client;

        Process proc = null;
        try {
            String envExe = System.getenv("RUST_ROUTER_SERVER_EXE");
            File exe = envExe != null ? new File(envExe) : null;
            if (exe == null || !exe.exists()) {
                String[] candidatePaths = {
                    "../scr-axum-cranker-router/target/debug/examples/unified_router_server",
                    "../scr-axum-cranker-router/target/release/examples/unified_router_server"
                };
                for (String path : candidatePaths) {
                    File f = new File(path + ".exe");
                    if (f.exists()) {
                        exe = f;
                        break;
                    }
                    f = new File(path);
                    if (f.exists()) {
                        exe = f;
                        break;
                    }
                }
            }
            if (exe == null || !exe.exists()) {
                throw new IllegalStateException("Rust unified_router_server binary not found. Please specify RUST_ROUTER_SERVER_EXE or run 'cargo build --example unified_router_server'");
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(exe.getCanonicalPath());
            cmd.add("--reg-port");
            cmd.add(String.valueOf(regPort));
            cmd.add("--visit-port");
            cmd.add(String.valueOf(visitPort));
            cmd.add("--routes-keep-time-millis");
            cmd.add(String.valueOf(routesKeepTimeMillis));
            cmd.add("--connector-max-wait-time-millis");
            cmd.add(String.valueOf(maxWaitInMillis));
            cmd.add("--via-name");
            String cleanVia = viaValue.replaceAll("[^a-zA-Z0-9]", "");
            if (cleanVia.isEmpty()) cleanVia = "mucranker";
            cmd.add(cleanVia);
            cmd.add("--discard-client-forwarded-headers");
            cmd.add(String.valueOf(discardClientForwardedHeaders));
            cmd.add("--send-legacy-forwarded-headers");
            cmd.add(String.valueOf(sendLegacyForwardedHeaders));
            cmd.add("--use-domain-filter");
            cmd.add("true");
            cmd.add("--idle-read-timeout-ms");
            cmd.add(String.valueOf(idleReadTimeoutMills));
            cmd.add("--tls");
            cmd.add(String.valueOf(RustTestHelper.isTlsMode()));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.to(new File("target/rust-router-" + regPort + ".log")));
            pb.redirectError(ProcessBuilder.Redirect.to(new File("target/rust-router-err-" + regPort + ".log")));
            proc = pb.start();

            // Wait for it to start up
            long start = System.currentTimeMillis();
            boolean started = false;
            while (System.currentTimeMillis() - start < 10000) {
                try {
                    HttpResponse<String> response = httpClient.send(
                            HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + regPort + "/health")).GET().build(),
                            HttpResponse.BodyHandlers.ofString()
                    );
                    if (response.statusCode() == 200) {
                        started = true;
                        break;
                    }
                } catch (Exception ignored) {
                }
                Thread.sleep(100);
            }
            if (!started) {
                throw new IllegalStateException("Rust unified_router_server failed to start on ports " + regPort + " and " + visitPort);
            }

        } catch (Exception e) {
            e.printStackTrace();
            if (proc != null) {
                proc.destroy();
            }
            throw new RuntimeException("Failed to launch Rust router process: " + e.getMessage(), e);
        }
        this.process = proc;
    }

    public int getRegPort() {
        return regPort;
    }

    public int getVisitPort() {
        return visitPort;
    }

    @Override
    public MuHandler createRegistrationHandler() {
        return new RustRegistrationHandler(this);
    }

    @Override
    public int idleConnectionCount() {
        try {
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + regPort + "/health/connectors")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() == 200) {
                JSONObject obj = new JSONObject(response.body());
                JSONObject services = obj.getJSONObject("services");
                int count = 0;
                for (String route : services.keySet()) {
                    JSONObject service = services.getJSONObject(route);
                    JSONArray connectors = service.getJSONArray("connectors");
                    for (int i = 0; i < connectors.length(); i++) {
                        JSONObject conn = connectors.getJSONObject(i);
                        count += conn.getInt("connectionCount");
                    }
                }
                if (count > 0) {
                    System.out.println("DEBUG: idleConnectionCount = " + count + ", JSON = " + obj);
                }
                return count;
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    @Override
    public MuHandler createHttpHandler() {
        return new RustHttpHandler(this);
    }

    public static class RustRegistrationHandler implements MuHandler {
        public final RustCrankerRouter router;
        public RustRegistrationHandler(RustCrankerRouter router) {
            this.router = router;
        }
        @Override
        public boolean handle(io.muserver.MuRequest request, io.muserver.MuResponse response) {
            return false;
        }
    }

    public static class RustHttpHandler implements MuHandler {
        public final RustCrankerRouter router;
        public RustHttpHandler(RustCrankerRouter router) {
            this.router = router;
        }
        @Override
        public boolean handle(io.muserver.MuRequest request, io.muserver.MuResponse response) {
            return false;
        }
    }

    @Override
    public RouterInfo collectInfo() {
        try {
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + regPort + "/health/connectors")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() == 200) {
                JSONObject obj = new JSONObject(response.body());
                JSONObject servicesObj = obj.getJSONObject("services");
                List<ConnectorService> services = new ArrayList<>();
                for (String route : servicesObj.keySet()) {
                    JSONObject serviceJson = servicesObj.getJSONObject(route);
                    String name = serviceJson.getString("name");
                    String componentName = serviceJson.optString("componentName", "unknown");
                    JSONArray connectorsJson = serviceJson.getJSONArray("connectors");
                    List<ConnectorInstance> connectors = new ArrayList<>();
                    for (int i = 0; i < connectorsJson.length(); i++) {
                        JSONObject connJson = connectorsJson.getJSONObject(i);
                        String ip = connJson.getString("ip");
                        String connectorInstanceID = connJson.getString("connectorInstanceID");
                        String connCompName = connJson.optString("componentName", componentName);
                        boolean darkMode = connJson.optBoolean("darkMode", false);
                        JSONArray connsJson = connJson.getJSONArray("connections");
                        ArrayList<ConnectorConnection> connections = new ArrayList<>();
                        for (int j = 0; j < connsJson.length(); j++) {
                            JSONObject cJson = connsJson.getJSONObject(j);
                            String socketID = cJson.getString("socketID");
                            int port = cJson.getInt("port");
                            String protocol = cJson.optString("protocol", "cranker_1.0");
                            int inflight = cJson.optInt("inflight", 0);
                            String domain = cJson.optString("domain", "*");
                            connections.add(new ConnectorConnectionImpl(domain, port, socketID, protocol, inflight));
                        }
                        connectors.add(new ConnectorInstanceImpl(ip, connectorInstanceID, connCompName, connections, darkMode));
                    }
                    services.add(new ConnectorServiceImpl(name, componentName, connectors));
                }
                return new RouterInfoImpl(services, Collections.emptySet(), Collections.emptyMap());
            }
        } catch (Exception ignored) {
        }
        return new RouterInfoImpl(Collections.emptyList(), Collections.emptySet(), Collections.emptyMap());
    }

    @Override
    public void stop() {
        if(process != null) {
            // 1. Handle all downstream descendant processes
            process.descendants().forEach(handle -> {
                if (handle.isAlive()) {
                    handle.destroy(); // Request graceful termination
                    // Optional: Forcefully kill if it doesn't respond quickly
                    try {
                        Thread.sleep(100);
                        if (handle.isAlive()) {
                            handle.destroyForcibly();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public DarkModeManager darkModeManager() {
        return new DarkModeManager() {
            private String toJson(DarkHost host) {
                return new JSONObject()
                        .put("address", host.address().getHostAddress())
                        .put("dateEnabled", host.dateEnabled().toEpochMilli())
                        .put("reason", host.reason() == null ? "" : host.reason())
                        .toString();
            }

            private void sendPost(String path, String json) {
                try {
                    httpClient.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create("http://127.0.0.1:" + regPort + path))
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(json))
                                    .build(),
                            HttpResponse.BodyHandlers.discarding()
                    );
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void enableDarkMode(DarkHost host) {
                sendPost("/dark-mode/enable", toJson(host));
            }

            @Override
            public void disableDarkMode(DarkHost host) {
                sendPost("/dark-mode/disable", toJson(host));
            }

            @Override
            public Set<DarkHost> darkHosts() {
                try {
                    HttpResponse<String> response = httpClient.send(
                            HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + regPort + "/dark-mode/hosts")).GET().build(),
                            HttpResponse.BodyHandlers.ofString()
                    );
                    if (response.statusCode() == 200) {
                        JSONArray arr = new JSONArray(response.body());
                        Set<DarkHost> hosts = new HashSet<>();
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject obj = arr.getJSONObject(i);
                            InetAddress address = InetAddress.getByName(obj.getString("address"));
                            Instant dateEnabled = Instant.ofEpochMilli(obj.getLong("dateEnabled"));
                            String reason = obj.optString("reason", "");
                            hosts.add(DarkHost.create(address, dateEnabled, reason));
                        }
                        return hosts;
                    }
                } catch (Exception e) {
                    // ignore
                }
                return Collections.emptySet();
            }

            @Override
            public Optional<DarkHost> findHost(java.net.InetAddress address) {
                return darkHosts().stream().filter(h -> h.address().equals(address)).findFirst();
            }
        };
    }

    private static boolean isPortFree(int port) {
        try (ServerSocket s = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("No free port available", e);
        }
    }
}
