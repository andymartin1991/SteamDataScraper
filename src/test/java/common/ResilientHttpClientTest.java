package common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ResilientHttpClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void retriesTemporaryServerErrorsUntilItRecovers() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/recover", exchange -> {
            int currentRequest = requests.incrementAndGet();
            byte[] body = (currentRequest < 3 ? "temporary" : "ok").getBytes(StandardCharsets.UTF_8);
            int status = currentRequest < 3 ? 503 : 200;
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        ResilientHttpClient client = testClient();
        String response = client.execute("http://127.0.0.1:" + server.getAddress().getPort() + "/recover");

        assertEquals("ok", response);
        assertEquals(3, requests.get());
    }

    @Test
    void retriesTransportDisconnectionUntilConnectionReturns() throws IOException {
        AtomicInteger connectionAttempts = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/online", exchange -> {
            byte[] body = "connected".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        ResilientHttpClient client = new ResilientHttpClient(
            1_000, 1_000, 0, 0, 0, millis -> { }, url -> {
                if (connectionAttempts.incrementAndGet() == 1) {
                    throw new ConnectException("VPN desconectada");
                }
                return (HttpURLConnection) new URL(url).openConnection();
            }
        );

        String response = client.execute("http://127.0.0.1:" + server.getAddress().getPort() + "/online");

        assertEquals("connected", response);
        assertEquals(2, connectionAttempts.get());
    }

    @Test
    void doesNotRetryPermanentClientErrors() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bad-request", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });
        server.start();

        ResilientHttpClient.HttpStatusException error = assertThrows(
            ResilientHttpClient.HttpStatusException.class,
            () -> testClient().execute("http://127.0.0.1:" + server.getAddress().getPort() + "/bad-request")
        );

        assertEquals(400, error.getStatusCode());
        assertEquals(1, requests.get());
    }

    @Test
    void classifiesOnlyTemporaryStatusesAsRetryable() {
        assertTrue(ResilientHttpClient.isRetryableStatus(408));
        assertTrue(ResilientHttpClient.isRetryableStatus(429));
        assertTrue(ResilientHttpClient.isRetryableStatus(503));
        assertFalse(ResilientHttpClient.isRetryableStatus(400));
        assertFalse(ResilientHttpClient.isRetryableStatus(401));
        assertFalse(ResilientHttpClient.isRetryableStatus(404));
    }

    @Test
    void canPassThroughRateLimitWithoutRetrying() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/quota", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        server.start();

        ResilientHttpClient.HttpStatusException error = assertThrows(
            ResilientHttpClient.HttpStatusException.class,
            () -> testClient().execute(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/quota",
                Set.of(429)
            )
        );

        assertEquals(429, error.getStatusCode());
        assertEquals(1, requests.get());
    }

    @Test
    void interruptionStopsTheInfiniteRetryLoop() {
        ResilientHttpClient client = new ResilientHttpClient(
            1_000, 1_000, 1, 1, 1,
            millis -> { throw new InterruptedException("stop"); },
            url -> { throw new ConnectException("offline"); }
        );

        try {
            assertThrows(
                ResilientHttpClient.RequestInterruptedException.class,
                () -> client.execute("http://127.0.0.1/unavailable")
            );
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static ResilientHttpClient testClient() {
        return new ResilientHttpClient(1_000, 1_000, 0, 0, 0, millis -> { });
    }
}
