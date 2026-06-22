package common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * Cliente HTTP sencillo para procesos largos de recoleccion.
 *
 * <p>Las desconexiones, los timeouts, los rate limits y los errores temporales
 * del servidor se reintentan sin limite. Los errores permanentes se devuelven
 * al llamador para evitar bucles infinitos por URLs o credenciales incorrectas.</p>
 */
public final class ResilientHttpClient {

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 15_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 15_000;
    private static final long DEFAULT_NETWORK_RETRY_MS = 5_000;
    private static final long MAX_NETWORK_RETRY_MS = 30_000;
    private static final long DEFAULT_SERVER_RETRY_MS = 60_000;
    private static final String USER_AGENT = "SteamDataScraper/2.0";

    private static final ResilientHttpClient DEFAULT = new ResilientHttpClient(
        DEFAULT_CONNECT_TIMEOUT_MS,
        DEFAULT_READ_TIMEOUT_MS,
        DEFAULT_NETWORK_RETRY_MS,
        MAX_NETWORK_RETRY_MS,
        DEFAULT_SERVER_RETRY_MS,
        Thread::sleep,
        url -> (HttpURLConnection) new URL(url).openConnection()
    );

    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final long networkRetryMs;
    private final long maxNetworkRetryMs;
    private final long serverRetryMs;
    private final Sleeper sleeper;
    private final ConnectionFactory connectionFactory;

    ResilientHttpClient(
        int connectTimeoutMs,
        int readTimeoutMs,
        long networkRetryMs,
        long maxNetworkRetryMs,
        long serverRetryMs,
        Sleeper sleeper
    ) {
        this(
            connectTimeoutMs,
            readTimeoutMs,
            networkRetryMs,
            maxNetworkRetryMs,
            serverRetryMs,
            sleeper,
            url -> (HttpURLConnection) new URL(url).openConnection()
        );
    }

    ResilientHttpClient(
        int connectTimeoutMs,
        int readTimeoutMs,
        long networkRetryMs,
        long maxNetworkRetryMs,
        long serverRetryMs,
        Sleeper sleeper,
        ConnectionFactory connectionFactory
    ) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.networkRetryMs = networkRetryMs;
        this.maxNetworkRetryMs = maxNetworkRetryMs;
        this.serverRetryMs = serverRetryMs;
        this.sleeper = sleeper;
        this.connectionFactory = connectionFactory;
    }

    public static String get(String url) {
        return DEFAULT.execute(url);
    }

    public static String getWithStatusPassthrough(String url, int... statusCodes) {
        Set<Integer> passthrough = new java.util.HashSet<>();
        for (int statusCode : statusCodes) {
            passthrough.add(statusCode);
        }
        return DEFAULT.execute(url, passthrough);
    }

    String execute(String urlString) {
        return execute(urlString, Set.of());
    }

    String execute(String urlString, Set<Integer> passthroughStatuses) {
        int failures = 0;

        while (true) {
            HttpURLConnection connection = null;
            try {
                connection = connectionFactory.open(urlString);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setConnectTimeout(connectTimeoutMs);
                connection.setReadTimeout(readTimeoutMs);

                int statusCode = connection.getResponseCode();
                if (statusCode >= 200 && statusCode < 300) {
                    String response = readResponse(connection);
                    if (failures > 0) {
                        System.out.println("✅ Conexión restablecida con " + safeEndpoint(urlString)
                            + " tras " + failures + " reintento(s).");
                    }
                    return response;
                }

                if (passthroughStatuses.contains(statusCode) || !isRetryableStatus(statusCode)) {
                    throw new HttpStatusException(statusCode, safeEndpoint(urlString));
                }

                failures++;
                long delayMs = retryAfterMillis(connection, serverRetryMs);
                System.err.println("⚠️ Respuesta temporal HTTP " + statusCode + " de "
                    + safeEndpoint(urlString) + ". Reintento " + failures + " en "
                    + formatDelay(delayMs) + ".");
                sleep(delayMs);
            } catch (HttpStatusException e) {
                throw e;
            } catch (IOException e) {
                failures++;
                long delayMs = networkDelayMillis(failures);
                System.err.println("⚠️ Sin conexión con " + safeEndpoint(urlString) + " ("
                    + describe(e) + "). Reintento " + failures + " en " + formatDelay(delayMs) + ".");
                sleep(delayMs);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }

    static boolean isRetryableStatus(int statusCode) {
        return statusCode == 408
            || statusCode == 425
            || statusCode == 429
            || statusCode >= 500 && statusCode <= 599;
    }

    private static String readResponse(HttpURLConnection connection) throws IOException {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
            return content.toString();
        }
    }

    private long networkDelayMillis(int failures) {
        int exponent = Math.min(Math.max(failures - 1, 0), 10);
        long multiplier = 1L << exponent;
        long calculated;
        try {
            calculated = Math.multiplyExact(networkRetryMs, multiplier);
        } catch (ArithmeticException e) {
            calculated = maxNetworkRetryMs;
        }
        return Math.min(calculated, maxNetworkRetryMs);
    }

    private static long retryAfterMillis(HttpURLConnection connection, long fallbackMs) {
        String retryAfter = connection.getHeaderField("Retry-After");
        if (retryAfter == null || retryAfter.isBlank()) {
            return fallbackMs;
        }

        try {
            return Math.max(0L, Duration.ofSeconds(Long.parseLong(retryAfter.trim())).toMillis());
        } catch (NumberFormatException ignored) {
            try {
                ZonedDateTime retryDate = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME);
                return Math.max(0L, Duration.between(ZonedDateTime.now(retryDate.getZone()), retryDate).toMillis());
            } catch (DateTimeParseException ignoredDate) {
                return fallbackMs;
            }
        }
    }

    private void sleep(long delayMs) {
        try {
            sleeper.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RequestInterruptedException("La espera de reconexión fue interrumpida", e);
        }
    }

    private static String safeEndpoint(String url) {
        return url.replaceAll("(?i)([?&](?:key|api_key)=)[^&]*", "$1***");
    }

    private static String describe(IOException exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName()
            + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static String formatDelay(long delayMs) {
        if (delayMs % 1_000 == 0) {
            return (delayMs / 1_000) + "s";
        }
        return delayMs + "ms";
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    interface ConnectionFactory {
        HttpURLConnection open(String url) throws IOException;
    }

    public static final class HttpStatusException extends RuntimeException {
        private final int statusCode;

        public HttpStatusException(int statusCode, String endpoint) {
            super("HTTP " + statusCode + " en " + endpoint);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }

    public static final class RequestInterruptedException extends RuntimeException {
        public RequestInterruptedException(String message, InterruptedException cause) {
            super(message, cause);
        }
    }
}
