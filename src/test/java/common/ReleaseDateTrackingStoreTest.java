package common;

import common.ReleaseDatePolicy.ReleaseInfo;
import common.ReleaseDatePolicy.Status;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseDateTrackingStoreTest {

    @Test
    void migratedCandidateIsDueAndStopsAfterReleaseDateIsRecovered() throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            ReleaseDateTrackingStore.ensureSchema(conn);
            ReleaseDateTrackingStore.recordMigratedCandidate(
                conn,
                42,
                new ReleaseInfo(Status.MISSING, null)
            );

            var due = ReleaseDateTrackingStore.loadDue(conn, 10);
            assertEquals(1, due.size());

            ReleaseDateTrackingStore.recordCheck(
                conn,
                42,
                new ReleaseInfo(Status.RELEASED, java.time.LocalDate.of(2020, 1, 1)),
                due.get(0).attemptCount()
            );

            assertTrue(ReleaseDateTrackingStore.loadDue(conn, 10).isEmpty());
        }
    }
}
