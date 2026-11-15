package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class RpcBridgeClientTest {
    private static final String TOKEN = "A".repeat(43);
    private static final String STATE = "{\"schemaVersion\":1,\"ok\":true,\"state\":{"
            + "\"schemaVersion\":1,\"bridgeInstanceId\":\"test-bridge\"}}";
    private static final String EVENTS = "{\"schemaVersion\":1,\"ok\":true,"
            + "\"bridgeInstanceId\":\"test-bridge\",\"events\":[]}";

    @Test
    public void closeDisconnectsAnOutstandingLongPollWithoutLateCallbacks() throws Exception {
        CountDownLatch eventsStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicInteger disconnected = new AtomicInteger();
        TestServer server = new TestServer(socket -> {
            eventsStarted.countDown();
            try {
                releaseResponse.await(10, TimeUnit.SECONDS);
                respond(socket, EVENTS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        RpcBridgeClient client = new RpcBridgeClient(TOKEN, server.port());
        try {
            client.startPolling(null, 0, new Listener() {
                @Override public void onConnected(JSONObject state) {
                    worker.set(Thread.currentThread());
                }
                @Override public void onDisconnected(String reason) {
                    disconnected.incrementAndGet();
                }
            });
            assertTrue(eventsStarted.await(3, TimeUnit.SECONDS));
            long closingStarted = System.nanoTime();
            client.close();
            assertTrue("Closing the client blocked the calling thread",
                    System.nanoTime() - closingStarted < TimeUnit.MILLISECONDS.toNanos(1_500L));
            worker.get().join(1_500L);
            assertFalse("Closing the client left its HTTP poll blocked", worker.get().isAlive());
            assertEquals("Closing is not a bridge failure", 0, disconnected.get());
        } finally {
            releaseResponse.countDown();
            client.close();
            server.close();
        }
    }

    @Test
    public void closeFromConnectedCallbackDoesNotStartAnotherRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        TestServer server = new TestServer(socket -> {
            requests.incrementAndGet();
            respond(socket, EVENTS);
        });
        RpcBridgeClient client = new RpcBridgeClient(TOKEN, server.port());
        try {
            client.startPolling(null, 0, new Listener() {
                @Override public void onConnected(JSONObject state) {
                    worker.set(Thread.currentThread());
                    client.close();
                    connected.countDown();
                }
            });
            assertTrue(connected.await(3, TimeUnit.SECONDS));
            worker.get().join(1_500L);
            assertFalse(worker.get().isAlive());
            assertEquals("A closed client sent an event request", 0, requests.get());
        } finally {
            client.close();
            server.close();
        }
    }

    private static void respond(Socket socket, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    /** A loopback fixture using only APIs available to Android's JVM test compiler. */
    private static final class TestServer implements AutoCloseable {
        interface EventsHandler { void handle(Socket socket) throws IOException; }

        private final ServerSocket server;
        private final ExecutorService handlers = Executors.newCachedThreadPool();
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();

        TestServer(EventsHandler events) throws IOException {
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            handlers.execute(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        sockets.add(socket);
                        handlers.execute(() -> {
                            try (socket;
                                 BufferedReader input = new BufferedReader(new InputStreamReader(
                                         socket.getInputStream(), StandardCharsets.US_ASCII))) {
                                socket.setSoTimeout(3_000);
                                String request = input.readLine();
                                String line;
                                while ((line = input.readLine()) != null && !line.isEmpty()) { }
                                if (request != null && request.startsWith("GET /v1/state ")) {
                                    respond(socket, STATE);
                                } else {
                                    events.handle(socket);
                                }
                            } catch (IOException ignored) {
                                // The close test deliberately disconnects before receiving a body.
                            } finally {
                                sockets.remove(socket);
                            }
                        });
                    } catch (IOException ignored) {
                        return;
                    }
                }
            });
        }

        int port() { return server.getLocalPort(); }

        @Override public void close() throws IOException {
            server.close();
            for (Socket socket : sockets) socket.close();
            handlers.shutdownNow();
        }
    }

    private static class Listener implements RpcBridgeClient.Listener {
        @Override public void onConnected(JSONObject state) { }
        @Override public void onEvent(BridgeEvent event) { }
        @Override public void onEventGap(String bridgeInstanceId, long earliestReceived) { }
        @Override public void onDisconnected(String reason) { }
    }
}
