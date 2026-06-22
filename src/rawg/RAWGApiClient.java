package rawg;

import common.ApiKeyConfig;
import common.ResilientHttpClient;
import common.ResilientHttpClient.HttpStatusException;

import java.util.regex.Matcher;

final class RAWGApiClient {

    private static final String RAWG_API_KEYS_ENV = "RAWG_API_KEYS";
    private static final String RAWG_API_KEYS_PROPERTY = "rawg.api.keys";

    @FunctionalInterface
    interface HttpGetter {
        String get(String url);
    }

    static final class ApiKeysExhaustedException extends SecurityException {
        ApiKeysExhaustedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final String[] apiKeys;
    private final HttpGetter httpGetter;
    private int currentKeyIndex;

    RAWGApiClient() {
        this(
            ApiKeyConfig.getRequiredCsvValues(
                RAWG_API_KEYS_PROPERTY,
                RAWG_API_KEYS_ENV,
                "las RAWG API keys"
            ),
            url -> ResilientHttpClient.getWithStatusPassthrough(url, 429)
        );
    }

    RAWGApiClient(String[] apiKeys, HttpGetter httpGetter) {
        if (apiKeys == null || apiKeys.length == 0) {
            throw new IllegalArgumentException("Se necesita al menos una RAWG API key");
        }
        this.apiKeys = apiKeys.clone();
        this.httpGetter = httpGetter;
    }

    String get(String urlTemplate) {
        int attemptedKeys = 0;
        HttpStatusException lastKeyError = null;

        while (attemptedKeys < apiKeys.length) {
            String url = urlTemplate.replaceAll(
                "key=[^&]+",
                Matcher.quoteReplacement("key=" + apiKeys[currentKeyIndex])
            );
            try {
                return httpGetter.get(url);
            } catch (HttpStatusException e) {
                if (e.getStatusCode() != 401 && e.getStatusCode() != 429) {
                    throw e;
                }

                attemptedKeys++;
                lastKeyError = e;
                String reason = e.getStatusCode() == 429 ? "cuota agotada" : "clave rechazada";
                System.err.println("⚠️ RAWG: " + reason + " para la clave "
                    + (currentKeyIndex + 1) + "/" + apiKeys.length + ".");

                if (attemptedKeys < apiKeys.length) {
                    currentKeyIndex = (currentKeyIndex + 1) % apiKeys.length;
                    System.out.println("🔄 Probando RAWG API key " + (currentKeyIndex + 1)
                        + "/" + apiKeys.length + "...");
                }
            }
        }

        throw new ApiKeysExhaustedException(
            "RAWG agotó o rechazó todas las API keys configuradas. " +
                "El proceso termina para evitar una rotación infinita hasta la renovación de cuota.",
            lastKeyError
        );
    }
}
