package rawg;

import common.ResilientHttpClient.HttpStatusException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RAWGApiClientTest {

    @Test
    void rotatesAfterQuotaErrorAndUsesNextKey() {
        List<String> urls = new ArrayList<>();
        RAWGApiClient client = new RAWGApiClient(new String[]{"key-one", "key-two"}, url -> {
            urls.add(url);
            if (url.contains("key=key-one")) {
                throw new HttpStatusException(429, "test");
            }
            return "ok";
        });

        assertEquals("ok", client.get("https://api.rawg.io/api/games?key=placeholder"));
        assertEquals(2, urls.size());
        assertEquals(true, urls.get(0).contains("key=key-one"));
        assertEquals(true, urls.get(1).contains("key=key-two"));
    }

    @Test
    void stopsAfterEveryKeyHasFailed() {
        List<String> urls = new ArrayList<>();
        RAWGApiClient client = new RAWGApiClient(new String[]{"one", "two", "three"}, url -> {
            urls.add(url);
            throw new HttpStatusException(429, "test");
        });

        assertThrows(
            RAWGApiClient.ApiKeysExhaustedException.class,
            () -> client.get("https://api.rawg.io/api/games?key=placeholder")
        );
        assertEquals(3, urls.size());
    }
}
