package common;

import common.ReleaseDatePolicy.Status;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReleaseDatePolicyTest {

    @Test
    void identifiesMissingSteamDateEvenWhenComingSoonIsFalse() {
        String json = "{\"10\":{\"success\":true,\"data\":{" +
            "\"release_date\":{\"coming_soon\":false,\"date\":\"\"}}}}";

        ReleaseDatePolicy.ReleaseInfo info = ReleaseDatePolicy.fromSteam(json);

        assertEquals(Status.MISSING, info.status());
        assertNull(info.releaseDate());
    }

    @Test
    void parsesBothKnownSteamDateOrders() {
        String dayFirst = "{\"10\":{\"data\":{\"release_date\":{" +
            "\"coming_soon\":true,\"date\":\"10 Jul, 2035\"}}}}";
        String monthFirst = "{\"11\":{\"data\":{\"release_date\":{" +
            "\"coming_soon\":true,\"date\":\"Jul 10, 2035\"}}}}";

        assertEquals(Status.FUTURE, ReleaseDatePolicy.fromSteam(dayFirst).status());
        assertEquals(Status.FUTURE, ReleaseDatePolicy.fromSteam(monthFirst).status());
    }

    @Test
    void identifiesRawgTbaWithoutReleaseDate() {
        ReleaseDatePolicy.ReleaseInfo info = ReleaseDatePolicy.fromRawg(
            "{\"id\":1,\"released\":null,\"tba\":true}"
        );

        assertEquals(Status.TBA, info.status());
    }

    @Test
    void increasesUnknownDateBackoffUpToNinetyDays() {
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);
        ReleaseDatePolicy.ReleaseInfo missing = new ReleaseDatePolicy.ReleaseInfo(Status.MISSING, null);

        assertEquals(now.plusDays(7), ReleaseDatePolicy.nextCheckAt(missing, 0, now));
        assertEquals(now.plusDays(30), ReleaseDatePolicy.nextCheckAt(missing, 2, now));
        assertEquals(now.plusDays(90), ReleaseDatePolicy.nextCheckAt(missing, 99, now));
    }
}
