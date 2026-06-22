package common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

public final class CollectorRunGuard {

    public record JsonTarget(String table, String idColumn, String jsonColumn, String syncColumn) {
        public JsonTarget {
            validateIdentifier(table);
            validateIdentifier(idColumn);
            validateIdentifier(jsonColumn);
            validateIdentifier(syncColumn);
        }
    }

    public static final class RunSession implements AutoCloseable {
        private final String dbFile;
        private final String processName;
        private boolean closed;

        private RunSession(String dbFile, String processName) {
            this.dbFile = dbFile;
            this.processName = processName;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            String sql = "UPDATE collector_runtime_state SET clean_shutdown=1, " +
                "process_id=NULL, last_clean_shutdown_at=CURRENT_TIMESTAMP WHERE process_name=?";
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, processName);
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new DataStoreException(
                    "No se pudo marcar el cierre limpio de " + processName,
                    e
                );
            }
        }
    }

    private record PreviousRun(boolean exists, boolean clean, Long processId, String startedAt) {
    }

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CollectorRunGuard() {
    }

    public static RunSession begin(
        String dbFile,
        String processName,
        JsonTarget... jsonTargets
    ) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile)) {
            ensureSchema(conn);
            PreviousRun previous = loadPreviousRun(conn, processName);

            if (previous.exists() && !previous.clean()) {
                if (previous.processId() != null && isProcessAlive(previous.processId())) {
                    throw new DataStoreException(
                        "Ya existe una ejecución activa de " + processName +
                            " (PID " + previous.processId() + ")",
                        null
                    );
                }
                verifyAfterUnexpectedShutdown(conn, processName, previous.startedAt(), jsonTargets);
            }

            markRunning(conn, processName);
            return new RunSession(dbFile, processName);
        } catch (SQLException e) {
            throw new DataStoreException("No se pudo iniciar la protección de " + processName, e);
        }
    }

    public static void requireCompleteJson(String json, String context) {
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isObject()) {
                throw new IOException("la raíz JSON no es un objeto");
            }
        } catch (IOException | RuntimeException e) {
            throw new DataStoreException(
                "Se rechazó una respuesta JSON incompleta en " + context +
                    ". No se insertará ni se dará por procesada.",
                e
            );
        }
    }

    private static void ensureSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS collector_runtime_state (" +
                "process_name TEXT PRIMARY KEY, " +
                "clean_shutdown INTEGER NOT NULL, " +
                "process_id INTEGER, " +
                "last_started_at TIMESTAMP NOT NULL, " +
                "last_clean_shutdown_at TIMESTAMP)");
        }
    }

    private static PreviousRun loadPreviousRun(Connection conn, String processName) throws SQLException {
        String sql = "SELECT clean_shutdown, process_id, last_started_at " +
            "FROM collector_runtime_state WHERE process_name=?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, processName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return new PreviousRun(false, true, null, null);
                long processId = rs.getLong("process_id");
                return new PreviousRun(
                    true,
                    rs.getBoolean("clean_shutdown"),
                    rs.wasNull() ? null : processId,
                    rs.getString("last_started_at")
                );
            }
        }
    }

    private static void markRunning(Connection conn, String processName) throws SQLException {
        String sql = "INSERT INTO collector_runtime_state(" +
            "process_name, clean_shutdown, process_id, last_started_at) " +
            "VALUES(?,0,?,CURRENT_TIMESTAMP) ON CONFLICT(process_name) DO UPDATE SET " +
            "clean_shutdown=0, process_id=excluded.process_id, " +
            "last_started_at=CURRENT_TIMESTAMP";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, processName);
            stmt.setLong(2, ProcessHandle.current().pid());
            stmt.executeUpdate();
        }
    }

    private static void verifyAfterUnexpectedShutdown(
        Connection conn,
        String processName,
        String previousStartedAt,
        JsonTarget[] jsonTargets
    ) throws SQLException {
        System.err.println("⚠️ Se detectó un cierre inesperado anterior de " + processName + ".");
        System.out.println("🔎 Verificando recuperación WAL, integridad SQLite y JSON escritos en esa ejecución...");

        try (Statement stmt = conn.createStatement()) {
            stmt.executeQuery("PRAGMA wal_checkpoint(PASSIVE)").close();
            try (ResultSet rs = stmt.executeQuery("PRAGMA quick_check(1)")) {
                String detail = rs.next() ? rs.getString(1) : "sin resultado";
                if (!"ok".equalsIgnoreCase(detail)) {
                    throw new DataStoreException(
                        "SQLite detectó corrupción tras el cierre inesperado de " + processName +
                            ": " + detail,
                        null
                    );
                }
            }
        }

        if (previousStartedAt != null) {
            for (JsonTarget target : jsonTargets) {
                verifyRecentJson(conn, processName, previousStartedAt, target);
            }
        }
        System.out.println("✅ Recuperación comprobada. No hay escrituras incompletas.");
    }

    private static void verifyRecentJson(
        Connection conn,
        String processName,
        String previousStartedAt,
        JsonTarget target
    ) throws SQLException {
        String sql = "SELECT " + target.idColumn() + " FROM " + target.table() +
            " WHERE " + target.syncColumn() + " >= ? AND (" + target.jsonColumn() +
            " IS NULL OR json_valid(" + target.jsonColumn() + ")=0) LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, previousStartedAt);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    throw new DataStoreException(
                        "Se encontró JSON incompleto o inválido tras el cierre de " + processName +
                            " en " + target.table() + " ID " + rs.getString(1) +
                            ". El proceso se detiene para no darlo por válido.",
                        null
                    );
                }
            }
        }
    }

    private static boolean isProcessAlive(long processId) {
        return ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void validateIdentifier(String identifier) {
        if (identifier == null || !SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Identificador SQLite no válido: " + identifier);
        }
    }
}
