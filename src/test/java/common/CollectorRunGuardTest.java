package common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CollectorRunGuardTest {

    @TempDir
    Path tempDir;

    @Test
    void validatesDatabaseAfterAnInterruptedRun() throws Exception {
        String dbFile = tempDir.resolve("valid.sqlite").toString();
        createRawTable(dbFile, "{\"id\":1}");

        try (CollectorRunGuard.RunSession ignored = CollectorRunGuard.begin(
            dbFile,
            "test-process",
            new CollectorRunGuard.JsonTarget("raw_data", "id", "json_data", "fecha_sync")
        )) {
            // Primera ejecución registrada y cerrada limpiamente.
        }
        markAsInterrupted(dbFile);

        assertDoesNotThrow(() -> {
            try (CollectorRunGuard.RunSession ignored = CollectorRunGuard.begin(
                dbFile,
                "test-process",
                new CollectorRunGuard.JsonTarget("raw_data", "id", "json_data", "fecha_sync")
            )) {
                // La recuperación debe aceptar el JSON válido.
            }
        });
    }

    @Test
    void stopsInsteadOfAcceptingInvalidJsonFromInterruptedRun() throws Exception {
        String dbFile = tempDir.resolve("invalid.sqlite").toString();
        createRawTable(dbFile, "{incomplete");
        try (CollectorRunGuard.RunSession ignored = CollectorRunGuard.begin(
            dbFile,
            "test-process",
            new CollectorRunGuard.JsonTarget("raw_data", "id", "json_data", "fecha_sync")
        )) {
            // Crear el estado inicial.
        }
        markAsInterrupted(dbFile);

        assertThrows(
            DataStoreException.class,
            () -> CollectorRunGuard.begin(
                dbFile,
                "test-process",
                new CollectorRunGuard.JsonTarget("raw_data", "id", "json_data", "fecha_sync")
            )
        );
    }

    @Test
    void preventsTwoInstancesOfTheSameWriter() throws Exception {
        String dbFile = tempDir.resolve("concurrent.sqlite").toString();
        createRawTable(dbFile, "{\"id\":1}");

        try (CollectorRunGuard.RunSession ignored = CollectorRunGuard.begin(
            dbFile,
            "test-process",
            new CollectorRunGuard.JsonTarget("raw_data", "id", "json_data", "fecha_sync")
        )) {
            assertThrows(
                DataStoreException.class,
                () -> CollectorRunGuard.begin(
                    dbFile,
                    "test-process",
                    new CollectorRunGuard.JsonTarget("raw_data", "id", "json_data", "fecha_sync")
                )
            );
        }
    }

    @Test
    void rejectsIncompletePayloadBeforeItCanBeStored() {
        assertThrows(
            DataStoreException.class,
            () -> CollectorRunGuard.requireCompleteJson("{incomplete", "test")
        );
    }

    private static void createRawTable(String dbFile, String json) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("CREATE TABLE raw_data(" +
                "id INTEGER PRIMARY KEY, json_data TEXT, fecha_sync TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            try (var insert = conn.prepareStatement(
                "INSERT INTO raw_data(id, json_data, fecha_sync) VALUES(1, ?, '2026-01-02 00:00:00')")) {
                insert.setString(1, json);
                insert.executeUpdate();
            }
        }
    }

    private static void markAsInterrupted(String dbFile) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement stmt = conn.createStatement()) {
            stmt.execute("UPDATE collector_runtime_state SET clean_shutdown=0, process_id=NULL, " +
                "last_started_at='2026-01-01 00:00:00'");
        }
    }
}
