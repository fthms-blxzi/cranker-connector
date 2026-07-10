package com.hsbc.cranker.connector;

import com.hsbc.cranker.mucranker.CrankerRouter;
import com.hsbc.cranker.mucranker.CrankerRouterBuilder;
import io.muserver.BaseWebSocket;
import io.muserver.DoneCallback;
import io.muserver.MuServer;
import io.muserver.MuWebSocketSession;
import io.muserver.WebSocketHandlerBuilder;
import jakarta.ws.rs.ClientErrorException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import scaffolding.AssertUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.muserver.MuServerBuilder.muServer;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

// @Disabled("Should run after the router alpha version is published")
public class WebSocketTest {

    private CrankerRouter crankerRouter;
    private MuServer registrationServer;
    private MuServer crankerServer;
    private MuServer targetServer;
    private CrankerConnector connector;
    private final HttpClient httpClient = HttpUtils.createHttpClientBuilder(true).build();

    private final List<String> receivedTexts = new CopyOnWriteArrayList<>();
    private final List<byte[]> receivedBinaries = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();
    private final CompletableFuture<Throwable> errorFuture = new CompletableFuture<>();

    @BeforeEach
    public void setup() {
        crankerRouter = CrankerRouterBuilder.crankerRouter()
            .withSupportedCrankerProtocols(List.of("1.0", "3.0", "3.1"))
            .start();

        registrationServer = muServer()
            .withHttpsPort(0)
            .addHandler(crankerRouter.createRegistrationHandler())
            .start();

        crankerServer = muServer()
            .withHttpsPort(0)
            .addHandler(crankerRouter.createHttpHandler())
            .start();

        targetServer = muServer()
            .withHttpPort(0)
            .addHandler(WebSocketHandlerBuilder.webSocketHandler()
                .withWebSocketFactory((request, responseHeaders) -> {
                    String path = request.uri().getPath();
                    if (path.endsWith("/my-ws-service/ws-partial")) {
                        return new BaseWebSocket() {
                            @Override
                            public void onConnect(MuWebSocketSession session) throws Exception {
                                super.onConnect(session);
                                session.sendText("Partial one", false, error -> session.sendText("Partial two", false, error1 -> session.sendText("Last one", true, error2 -> {
                                    try {
                                        session.close(1000, "Done");
                                    } catch (Exception ignored) {
                                    }
                                })));
                            }
                        };
                    } else if (path.endsWith("/my-ws-service/ws-error-handshake")) {
                        throw new ClientErrorException("Handshake custom error", 409);
                    } else {
                        return new BaseWebSocket() {
                            @Override
                            public void onText(String message, boolean isLast, DoneCallback onComplete) throws Exception {
                                if (message.equals("kill-me")) {
                                    session().close(1008, "Abrupt target close");
                                    onComplete.onComplete(null);
                                    return;
                                }
                                session().sendText("Echo: " + message, onComplete);
                            }

                            @Override
                            public void onBinary(ByteBuffer buffer, boolean isLast, DoneCallback onComplete) throws Exception {
                                session().sendBinary(buffer, onComplete);
                            }
                        };
                    }
                })
            ).start();

        connector = CrankerConnectorBuilder.connector()
            .withPreferredProtocols(List.of("cranker_3.1"))
            .withHttpClient(CrankerConnectorBuilder.createHttpClient(true).build())
            .withTarget(targetServer.uri())
            .withRoute("my-ws-service")
            .withRouterUris(RegistrationUriSuppliers.fixedUris(List.of(
                URI.create("ws" + registrationServer.uri().toString().substring(4))
            )))
            .withSlidingWindowSize(5)
            .start();
    }

    @AfterEach
    public void teardown() {
        if (connector != null) {
            connector.stop(5, TimeUnit.SECONDS);
        }
        if (targetServer != null) {
            targetServer.stop();
        }
        if (crankerServer != null) {
            crankerServer.stop();
        }
        if (registrationServer != null) {
            registrationServer.stop();
        }
        if (crankerRouter != null) {
            crankerRouter.stop();
        }
    }

    @Test
    public void testWebSocketBidirectionalCommunication() throws Exception {
        //Wait for registration to complete
        AssertUtils.assertEventually(() -> !crankerRouter.collectInfo().services().isEmpty(), is(true));

        URI wsClientUri = URI.create("ws" + crankerServer.uri().toString().substring(4) + "/my-ws-service/ws");

        CompletableFuture<WebSocket> wsFuture = httpClient.newWebSocketBuilder()
            .buildAsync(wsClientUri, new WebSocket.Listener() {
                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    receivedTexts.add(data.toString());
                    webSocket.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                    byte[] bytes = new byte[data.remaining()];
                    data.get(bytes);
                    receivedBinaries.add(bytes);
                    webSocket.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    closeFuture.complete(null);
                    return null;
                }

                @Override
                public void onError(WebSocket webSocket, Throwable error) {
                    errorFuture.complete(error);
                }
            });

        WebSocket clientWs = wsFuture.get(10, TimeUnit.SECONDS);
        assertNotNull(clientWs);

        // Test Text messages
        clientWs.sendText("Hello Cranker WS!", true).get(5, TimeUnit.SECONDS);
        AssertUtils.assertEventually(() -> !receivedTexts.isEmpty(), is(true));
        assertEquals("Echo: Hello Cranker WS!", receivedTexts.get(0));

        // Test Binary messages
        byte[] rawBytes = "Binary data sequence".getBytes(StandardCharsets.UTF_8);
        clientWs.sendBinary(ByteBuffer.wrap(rawBytes), true).get(5, TimeUnit.SECONDS);
        AssertUtils.assertEventually(() -> !receivedBinaries.isEmpty(), is(true));
        assertArrayEquals(rawBytes, receivedBinaries.get(0));

        // Test Closure
        clientWs.sendClose(1000, "Clean close").get(5, TimeUnit.SECONDS);
        assertDoesNotThrow(() -> closeFuture.get(10, TimeUnit.SECONDS));
    }

    @Test
    public void testWebSocketHandshakeHeadersAndPingPong() throws Exception {
        //Wait for registration to complete
        AssertUtils.assertEventually(() -> !crankerRouter.collectInfo().services().isEmpty(), is(true));

        URI wsClientUri = URI.create(getCrankedWsUrl() + "/my-ws-service/ws");

        CompletableFuture<Void> pingPongLatch = new CompletableFuture<>();
        CompletableFuture<WebSocket> wsFuture = httpClient.newWebSocketBuilder()
            .header("Custom-Test-Header", "HandshakeHeaderPayload")
            .buildAsync(wsClientUri, new WebSocket.Listener() {
                @Override
                public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
                    byte[] data = new byte[message.remaining()];
                    message.get(data);
                    String response = new String(data, StandardCharsets.UTF_8);
                    if ("ping-payload".equals(response)) {
                        pingPongLatch.complete(null);
                    }
                    webSocket.request(1);
                    return null;
                }
            });

        WebSocket clientWs = wsFuture.get(10, TimeUnit.SECONDS);
        assertNotNull(clientWs);

        // Send a ping and assert pong pyload matching
        ByteBuffer pingPayload = ByteBuffer.wrap("ping-payload".getBytes(StandardCharsets.UTF_8));
        clientWs.sendPing(pingPayload).get(5, TimeUnit.SECONDS);

        assertDoesNotThrow(() -> pingPongLatch.get(5, TimeUnit.SECONDS));
        clientWs.sendClose(1000, "Done").get(5, TimeUnit.SECONDS);
    }

    @NotNull
    private String getCrankedWsUrl() {
        return "ws" + crankerServer.uri().toString().substring(4);
    }

    @Test
    public void testWebSocketPartialWritesAreMultiplexedCorrectly() throws Exception {
        //Wait for registration to complete
        AssertUtils.assertEventually(() -> !crankerRouter.collectInfo().services().isEmpty(), is(true));

        URI wsClientUri = URI.create(getCrankedWsUrl() + "/my-ws-service/ws-partial");
        BlockingDeque<String> responseEvents = new LinkedBlockingDeque<>();
        CompletableFuture<Void> clientCloseLatch = new CompletableFuture<>();
        CompletableFuture<Throwable> clientErrorLatch = new CompletableFuture<>();

        CompletableFuture<WebSocket> wsFuture = httpClient.newWebSocketBuilder()
            .buildAsync(wsClientUri, new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket webSocket) {
                    webSocket.request(16);
                }

                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    responseEvents.add(data.toString());
                    webSocket.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    clientCloseLatch.complete(null);
                    return null;
                }

                @Override
                public void onError(WebSocket webSocket, Throwable error) {
                    clientErrorLatch.complete(error);
                }
            });

        WebSocket clientWs = wsFuture.get(10, TimeUnit.SECONDS);
        assertNotNull(clientWs);

        String expected = "Partial one Partial two Last one";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        List<String> actualParts = new ArrayList<>();
        while (!clientCloseLatch.isDone() && !clientErrorLatch.isDone() && System.nanoTime() < deadline) {
            long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            String next = responseEvents.poll(remainingMillis, TimeUnit.MILLISECONDS);
            if (next != null) {
                actualParts.add(next);
            }
        }

        assertFalse(clientErrorLatch.isDone(), "Client received unexpected websocket error: " + clientErrorLatch.getNow(null));

        int cursor = 0;
        for (String actualPart : actualParts) {
            int nextIndex = expected.indexOf(actualPart, cursor);
            assertTrue(
                nextIndex>=cursor,
                "Observed WebSocket text fragment out of order. expectedSequence="  + expected+ ", actualParts=" + actualParts
            );
            cursor = nextIndex + actualPart.length();
        }
        clientWs.abort();
    }

    @Test
    public void testWebSocketHandshakeErrorIsReturnedToClient() {
        //Wait for registration to complete
        AssertUtils.assertEventually(() -> !crankerRouter.collectInfo().services().isEmpty(), is(true));

        URI wsClientUri = URI.create(getCrankedWsUrl() + "/my-ws-service/ws-error-handshake");

        CompletableFuture<WebSocket> errorWsFuture = httpClient.newWebSocketBuilder()
            .buildAsync(wsClientUri, new WebSocket.Listener() {
            });

        ExecutionException ex = assertThrows(ExecutionException.class, () -> errorWsFuture.get(10, TimeUnit.SECONDS));
        assertNotNull(ex.getCause());
        assertInstanceOf(WebSocketHandshakeException.class, ex.getCause());
        WebSocketHandshakeException wsHandshakeEx = ((WebSocketHandshakeException) ex.getCause());
        assertEquals(502, wsHandshakeEx.getResponse().statusCode());
    }

    @Test
    public void testWebSocketFuzzingStress() throws Exception {
        //Wait for registration to complete
        AssertUtils.assertEventually(() -> !crankerRouter.collectInfo().services().isEmpty(), is(true));

        URI wsClientUri = URI.create(getCrankedWsUrl() + "/my-ws-service/ws");

        int clientCount = 10;
        int messagePerClient = 50;
        ExecutorService executorService = Executors.newFixedThreadPool(clientCount);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        final AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < clientCount; i++) {
            final int clientId = i;
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    List<String> expectedTexts = new ArrayList<>();
                    BlockingQueue<String> responseQueue = new LinkedBlockingDeque<>();

                    WebSocket clientWs = httpClient.newWebSocketBuilder()
                        .buildAsync(wsClientUri, new WebSocket.Listener() {
                            @Override
                            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                                responseQueue.add(data.toString());
                                webSocket.request(1);
                                return null;
                            }
                        }).get(10, TimeUnit.SECONDS);

                    Random r = new Random();
                    for (int j = 0; j < messagePerClient; j++) {
                        String payload = "Client-" + clientId + "-Msg-" + j + "-" + r.nextInt(10000);
                        expectedTexts.add("Echo: " + payload);
                        clientWs.sendText(payload, true).get(5, TimeUnit.SECONDS);
                    }

                    for (String expected : expectedTexts) {
                        String response = responseQueue.poll(10, TimeUnit.SECONDS);
                        assertNotNull(response, "Did not get response for " + expected);
                        assertEquals(expected, response);
                        successCount.incrementAndGet();
                    }

                    clientWs.sendClose(1000, "Done").get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    fail("Stress client " + clientId + " failed: " + e.getMessage());
                }
            }, executorService));
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        assertEquals(clientCount * messagePerClient, successCount.get());
        executorService.shutdown();
    }

    @Test
    public void testWebSocketFailurePatterns() throws Exception {
        AssertUtils.assertEventually(() -> !crankerRouter.collectInfo().services().isEmpty(), is(true));

        URI wsClientUri = URI.create(getCrankedWsUrl() + "/my-ws-service/ws");

        // Pattern 1: Connect to non-existing service (should fail upgrade or throw error)
        URI invalidWsCLientUri = URI.create(getCrankedWsUrl() + "/non-existent-service/ws");
        CompletableFuture<WebSocket> invalidWsFuture = httpClient.newWebSocketBuilder()
            .buildAsync(invalidWsCLientUri, new WebSocket.Listener() {
            });
        assertThrows(Exception.class, () -> invalidWsFuture.get(5, TimeUnit.SECONDS));

        //Pattern 2: Server-side connection termination midway
        CompletableFuture<Throwable> errorLatch = new CompletableFuture<>();
        CompletableFuture<Void> closeLatch = new CompletableFuture<>();
        try {
            WebSocket clientWs = httpClient.newWebSocketBuilder()
                .buildAsync(wsClientUri, new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        System.out.println("Pattern 2 client received Close frame: code=" + statusCode + ", reason=" + reason);
                        closeLatch.complete(null);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        System.out.println("Pattern 2 client received Error notification: " + error.getMessage());
                        errorLatch.complete(error);
                    }
                }).get(5, TimeUnit.SECONDS);

            assertNotNull(clientWs);
            System.out.println("Pattern 2 client connected. Sending kill-me command...");

            clientWs.sendText("kill-me", true);

            // Verify the client receives clean error notifications or drops
            final WebSocket finalClientWs = clientWs;
            AssertUtils.assertEventually(() -> {
                boolean inputClpsed = finalClientWs.isInputClosed();
                boolean outputClosed = finalClientWs.isOutputClosed();
                boolean errDone = errorLatch.isDone();
                boolean closeDone = closeLatch.isDone();
                String wsStr = finalClientWs.toString();
                boolean isClosedStr = wsStr.toLowerCase().contains("closed");
                System.out.printf("Checking disconnection condition details: inputClosed=%b, outputClosed=%b, errorLatchDone=%b, closeLatchDone=%b, wsStr=%s, isClosedStr=%b\n", inputClpsed, outputClosed, errDone, closeDone, wsStr, isClosedStr);
                return inputClpsed || outputClosed || errDone || closeDone || isClosedStr;
            }, is(true));
            System.out.println("Disconnection pattern verified");
        } catch (Exception e) {
            System.err.println("FailurePatterns test caught exception: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
}
