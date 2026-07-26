package com.hsbc.cranker.connector;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A command line wrapper for starting CrankerConnector in sidecar mode.
 */
public class CommandLineConnector {
    /**
     * Prevents instantiation of CommandLineConnector.
     */
    private CommandLineConnector() {}

    /**
     * Runs the command line connector.
     * @param args Command line arguments
     * @throws Exception if something goes wrong
     */
    public static void main(String[] args) throws Exception {
        String domain = "*";
        String route = "*";
        String target = null;
        List<String> registrationUris = new ArrayList<>();
        List<String> preferredProtocols = List.of("cranker_3.0", "cranker_1.0");
        String componentName = "cranker-connector";
        int slidingWindow = 2;

        for (int i = 0; i < args.length; i++) {
            if ("--domain".equals(args[i])) {
                domain = args[++i];
            } else if ("--route".equals(args[i])) {
                route = args[++i];
                if ("catch-all".equals(route)) {
                    route = "*";
                }
            } else if ("--target".equals(args[i])) {
                target = args[++i];
            } else if ("--registration-uris".equals(args[i])) {
                registrationUris = List.of(args[++i].split(","));
            } else if ("--preferred-protocols".equals(args[i])) {
                preferredProtocols = List.of(args[++i].split(","));
            } else if ("--component-name".equals(args[i])) {
                componentName = args[++i];
            } else if ("--sliding-window".equals(args[i])) {
                slidingWindow = Integer.parseInt(args[++i]);
            }
        }

        if (target == null || registrationUris.isEmpty()) {
            System.err.println("Usage: CommandLineConnector --target <target-uri> --registration-uris <comma-separated-uris> [options]");
            System.exit(1);
        }

        List<URI> uris = registrationUris.stream().map(URI::create).collect(Collectors.toList());

        CrankerConnector connector = CrankerConnectorBuilder.connector()
                .withPreferredProtocols(preferredProtocols)
                .withHttpClient(CrankerConnectorBuilder.createHttpClient(true).build())
                .withDomain(domain)
                .withRouterUris(RegistrationUriSuppliers.fixedUris(uris))
                .withComponentName(componentName)
                .withRoute(route)
                .withTarget(URI.create(target))
                .withSlidingWindowSize(slidingWindow)
                .withProxyEventListener(new ProxyEventListener() {
                    @Override
                    public java.net.http.HttpRequest beforeProxyToTarget(java.net.http.HttpRequest request, java.net.http.HttpRequest.Builder requestBuilder) {
                        System.out.println("beforeProxyToTarget: " + request.uri());
                        return request;
                    }
                    @Override
                    public void onProxyError(java.net.http.HttpRequest request, Throwable error) {
                        System.err.println("onProxyError: " + request.uri() + " error=" + error.getMessage());
                        error.printStackTrace();
                    }
                })
                .withRouterRegistrationListener(new RouterEventListener() {
                    @Override
                    public void onSocketConnectionError(RouterRegistration router, Throwable exception) {
                        System.err.println("onSocketConnectionError: " + exception.getMessage());
                        exception.printStackTrace();
                    }
                })
                .start();

        System.out.println("Connector started successfully: id=" + connector.connectorId());

        Thread.currentThread().join();
    }
}
