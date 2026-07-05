package com.hsbc.cranker.connector;

import io.muserver.MuHandler;
import io.muserver.MuServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.httpServer;
import static org.junit.jupiter.api.Assertions.*;

public class ConcurrentUploadTest extends BaseEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentUploadTest.class);

    private volatile MuHandler handler = (request, response) -> false;

    protected MuServer targetServer = httpServer()
            .addHandler((request, response) -> handler.handle(request, response))
            .start();

    private CrankerConnector connector;
    private java.util.concurrent.ExecutorService clientExecutor;
    private java.net.http.HttpClient localClient;

    @BeforeEach
    void setUp(RepetitionInfo repetitionInfo) {
        clientExecutor = java.util.concurrent.Executors.newFixedThreadPool(20);
        localClient = HttpUtils.createHttpClientBuilder(true)
                .executor(clientExecutor)
                .build();

        connector = CrankerConnectorBuilder.connector()
                .withPreferredProtocols(preferredProtocols(repetitionInfo))
                .withHttpClient(CrankerConnectorBuilder.createHttpClient(true).build())
                .withRouterUris(RegistrationUriSuppliers.fixedUris(registrationUri(registrationServer.uri())))
                .withRoute("upload-service")
                .withTarget(targetServer.uri())
                .withProxyEventListener(new ProxyEventListener() {
                    @Override
                    public void onProxyError(HttpRequest request, Throwable error) {
                        log.warn("onProxyError, request=" + request, error);
                    }
                })
                .withComponentName("cranker-connector-unit-test")
                .withSlidingWindowSize(10)
                .start();

        waitForRegistration("upload-service", connector.connectorId(), 2, crankerRouter);
    }

    @AfterEach
    public void stop() throws Exception {
        if (connector != null)
            assertTrue(connector.stop(10, TimeUnit.SECONDS));
        if (targetServer != null)
            targetServer.stop();
        if (clientExecutor != null)
            clientExecutor.shutdownNow();
    }

    @RepeatedTest(3)
    public void postLargeBody() throws InterruptedException {

        handler = (request, response) -> {
            response.status(200);
            response.write(request.readBodyAsString());
            return true;
        };

        // Explicitly assert and wait for our registered target is perfectly mapped
        // in crankerRouter state before sending arbitrary client requests
        waitForRegistration("upload-service", connector.connectorId(), 2, crankerRouter);

        Queue<String> orderTracker = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Queue<HttpResponse<String>> responses = new ConcurrentLinkedQueue<>();
        CountDownLatch countDownLatch = new CountDownLatch(10);
        java.util.concurrent.atomic.AtomicInteger requestOrder = new java.util.concurrent.atomic.AtomicInteger(0);

        final String body = "c".repeat(10 * 1000);
        for (int i = 0; i < 10; i++) {
            final int finalI = i;
            new Thread(() -> {
                int order = requestOrder.incrementAndGet();
                try {
                    URI uri = crankerServer.uri().resolve("/upload-service/?task=" + finalI);
                    System.out.println("LAUNCHING REQUEST #" + order + " for task=" + finalI + " URI: " + uri);
                    HttpResponse<String> resp = localClient.send(HttpRequest.newBuilder()
                            .method("POST", HttpRequest.BodyPublishers.ofString(body))
                            .uri(uri)
                            .build(), HttpResponse.BodyHandlers.ofString());
                    System.out.println("COMPLETED REQUEST #" + order + " for task=" + finalI + " with status=" + resp.statusCode());
                    responses.add(resp);
                } catch (Exception e) {
                    log.error("Concurrent request error", e);
                    System.err.println("FAILED REQUEST #" + order + " for task=" + finalI);
                } finally {
                    countDownLatch.countDown();
                }
            }).start();
        }

        assertTrue(countDownLatch.await(30, TimeUnit.SECONDS));
        assertEquals(10, responses.size());
        for (HttpResponse<String> response : responses) {
            assertNotNull(response);
            assertEquals(200, response.statusCode());
            assertEquals(body, response.body());
        }
    }
}
