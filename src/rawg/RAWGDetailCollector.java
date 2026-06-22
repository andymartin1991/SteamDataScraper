package rawg;

import common.DataStoreException;
import common.CollectorRunGuard;
import common.CollectorRunGuard.JsonTarget;
import common.CollectorRunGuard.RunSession;
import common.ResilientHttpClient.HttpStatusException;
import common.ResilientHttpClient.RequestInterruptedException;
import common.ReleaseDatePolicy;
import common.ReleaseDateTrackingStore;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class RAWGDetailCollector {

    private static final String DB_FILE = "data/db/rawg_raw.sqlite";
    private static final RAWGApiClient API_CLIENT = new RAWGApiClient();
    
    // --- CONFIGURACIÓN DE OPTIMIZACIÓN (COOLDOWNS) ---
    private static final int DIAS_COOLDOWN_VACIOS = 10; // Si falló/estaba vacío, esperar 10 días

    // Regex pre-compilada para rendimiento
    private static final Pattern PATTERN_PLATFORMS = Pattern.compile("\"parent_platforms\":\\s*\\[(.*?)\\]");

    private static class GameTask {
        int id;
        boolean tieneDetalle;
        boolean esError404;
        boolean esReintentoVacio;
        boolean descripcionVacia;
        boolean esSoloPC;

        public GameTask(int id, boolean tieneDetalle, boolean esError404, boolean esReintentoVacio, boolean descripcionVacia, boolean esSoloPC) {
            this.id = id;
            this.tieneDetalle = tieneDetalle;
            this.esError404 = esError404;
            this.esReintentoVacio = esReintentoVacio;
            this.descripcionVacia = descripcionVacia;
            this.esSoloPC = esSoloPC;
        }
    }
    
    private static class Stats {
        int total = 0;
        int pcOnly = 0;
        int consoleMulti = 0;
    }

    public static void main(String[] args) {
        RunSession runSession = null;
        try {
            System.out.println("🚀 Iniciando RAWG Detail Collector (Optimizado: Cooldowns Inteligentes + Lanzamiento Inmediato)...");

            Class.forName("org.sqlite.JDBC");
            ensureParentDir(DB_FILE);
            setupDatabase();
            runSession = CollectorRunGuard.begin(
                DB_FILE,
                "RAWGDetailCollector",
                new JsonTarget("rawg_details_data", "game_id", "json_full", "fecha_sync")
            );

            // 1. Analizar lo que YA tenemos guardado
            System.out.println("📊 Analizando base de datos existente...");
            Stats statsProcesados = analizarJuegosProcesados();
            
            // 2. Obtener y clasificar lo pendiente
            System.out.println("📋 Obteniendo lista de pendientes (aplicando filtros de tiempo)...");
            List<GameTask> pendientes = obtenerTareasPendientes();
            
            // Calcular estadísticas de pendientes
            long pendientesSoloPC = pendientes.stream().filter(t -> t.esSoloPC).count();
            long pendientesPrioridad = pendientes.size() - pendientesSoloPC;

            // 3. MOSTRAR DASHBOARD
            System.out.println("\n=================================================");
            System.out.println("       ESTADO DEL SCRAPING (RAWG)       ");
            System.out.println("=================================================");
            System.out.println(String.format("| %-15s | %-10s | %-10s | %-10s |", "CATEGORIA", "TOTAL", "CONSOLA/MULTI", "SOLO PC"));
            System.out.println("|-----------------|------------|---------------|------------|");
            System.out.println(String.format("| ✅ PROCESADOS   | %-10d | %-13d | %-10d |", 
                statsProcesados.total, statsProcesados.consoleMulti, statsProcesados.pcOnly));
            System.out.println(String.format("| ⏳ PENDIENTES   | %-10d | %-13d | %-10d |", 
                pendientes.size(), pendientesPrioridad, pendientesSoloPC));
            System.out.println("|-----------------|------------|---------------|------------|");
            System.out.println("=================================================\n");

            // ORDENAR: Prioridad a Consolas/Multi
            Collections.sort(pendientes, new Comparator<GameTask>() {
                @Override
                public int compare(GameTask o1, GameTask o2) {
                    if (o1.esSoloPC && !o2.esSoloPC) return 1;
                    if (!o1.esSoloPC && o2.esSoloPC) return -1;
                    return 0;
                }
            });

            int procesados = 0;
            for (GameTask tarea : pendientes) {
                try {
                    if (tarea.esError404) continue;

                    if (tarea.tieneDetalle) {
                        if (tarea.descripcionVacia) {
                            System.out.println("🔄 [" + procesados + "/" + pendientes.size() + "] ID " + tarea.id + ": Descripción vacía. Reintentando...");
                            procesarDescargaCompleta(tarea.id);
                            procesados++;
                            sleepOrStop(1000);
                            continue; 
                        }

                        String motivo = tarea.esReintentoVacio ? "Reintento Stores" : "Nuevo Stores";
                        String jsonStores = descargarStoresJuego(tarea.id);
                        
                        if (jsonStores == null || "404".equals(jsonStores)) jsonStores = "{\"results\":[]}";

                        actualizarStores(tarea.id, jsonStores);
                        procesados++;
                        
                        String tipo = tarea.esSoloPC ? "PC" : "CONSOLA";
                        System.out.println("✅ [" + procesados + "/" + pendientes.size() + "] Stores (" + tipo + "): ID " + tarea.id);
                        
                        sleepOrStop(1000);
                    } else {
                        procesarDescargaCompleta(tarea.id);
                        procesados++;
                        String tipo = tarea.esSoloPC ? "PC" : "CONSOLA";
                        System.out.println("✅ [" + procesados + "/" + pendientes.size() + "] Full (" + tipo + "): ID " + tarea.id);
                        sleepOrStop(1000);
                    }

                } catch (RuntimeException e) {
                    if (e instanceof SecurityException
                        || e instanceof RequestInterruptedException
                        || e instanceof DataStoreException) {
                        throw e;
                    }
                    System.err.println("❌ Error en ID " + tarea.id + ": " + e.getMessage());
                }
            }
            
            System.out.println("🏁 Proceso finalizado.");

        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("No se encontró el driver JDBC de SQLite", e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("RAWGDetailCollector finalizó con error", e);
        } finally {
            if (runSession != null) runSession.close();
        }
    }

    // --- LÓGICA DE CLASIFICACIÓN ---

    private static boolean esSoloPC(String jsonBasic) {
        if (jsonBasic == null) return false; 
        Matcher m = PATTERN_PLATFORMS.matcher(jsonBasic);
        if (m.find()) {
            String platformsContent = m.group(1);
            int count = 0;
            int idx = 0;
            while ((idx = platformsContent.indexOf("\"slug\":", idx)) != -1) {
                count++;
                idx += 7;
            }
            boolean tienePC = platformsContent.contains("\"slug\":\"pc\"");
            return (count == 1 && tienePC);
        }
        return false; 
    }
    
    // --- BASE DE DATOS Y ANÁLISIS ---

    private static Stats analizarJuegosProcesados() {
        Stats stats = new Stats();
        String sql = "SELECT r.json_data FROM rawg_details_data d " +
                     "JOIN rawg_raw_data r ON d.game_id = r.game_id " +
                     "WHERE d.json_full IS NOT NULL";
                     
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                stats.total++;
                if (esSoloPC(rs.getString("json_data"))) {
                    stats.pcOnly++;
                } else {
                    stats.consoleMulti++;
                }
            }
        } catch (SQLException e) {
            throw new DataStoreException("No se pudieron calcular las estadísticas RAWG", e);
        }
        return stats;
    }

    private static List<GameTask> obtenerTareasPendientes() {
        List<GameTask> tareas = new ArrayList<>();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        
        String sql = "SELECT r.game_id, r.json_data, " +
                     "d.json_full, d.json_stores, d.fecha_sync, " +
                     "CASE WHEN d.json_full IS NOT NULL THEN 1 ELSE 0 END as tiene_detalle, " +
                     "CASE WHEN d.json_full LIKE '%\"error\":\"404_not_found\"%' THEN 1 ELSE 0 END as es_error " +
                     "FROM rawg_raw_data r " +
                     "LEFT JOIN rawg_details_data d ON r.game_id = d.game_id";
        
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                if (rs.getBoolean("es_error")) continue;

                int gameId = rs.getInt("game_id");
                boolean tieneDetalle = rs.getBoolean("tiene_detalle");
                String jsonStores = rs.getString("json_stores");
                String jsonFull = rs.getString("json_full");
                String fechaSyncStr = rs.getString("fecha_sync"); 
                
                LocalDateTime fechaSync = null;
                if (fechaSyncStr != null) {
                    try {
                        fechaSync = LocalDateTime.parse(fechaSyncStr, dtf);
                    } catch (DateTimeParseException e) {
                        fechaSync = LocalDateTime.MIN;
                    }
                }

                boolean esVacioStores = (jsonStores != null && (jsonStores.contains("\"results\":[]") || jsonStores.equals("[]")));
                boolean esVacioDesc = (jsonFull != null && (jsonFull.contains("\"description\":\"\"") || jsonFull.contains("\"description_raw\":\"\"")));
                
                // --- LÓGICA DE COOLDOWN (OPTIMIZACIÓN) ---
                boolean debeReintentarVacio = false;
                if (esVacioStores || esVacioDesc) {
                    if (fechaSync == null || fechaSync.isBefore(LocalDateTime.now().minusDays(DIAS_COOLDOWN_VACIOS))) {
                        debeReintentarVacio = true;
                    }
                }

                // DECISIÓN FINAL
                boolean esNuevoSinDetalle = !tieneDetalle;
                boolean esNuevoSinStores = (tieneDetalle && jsonStores == null);
                
                if (esNuevoSinDetalle || esNuevoSinStores || debeReintentarVacio) {
                    tareas.add(new GameTask(
                        gameId,
                        tieneDetalle,
                        false, 
                        debeReintentarVacio,
                        esVacioDesc,
                        esSoloPC(rs.getString("json_data"))
                    ));
                }
            }
        } catch (SQLException e) {
            throw new DataStoreException("No se pudieron cargar las tareas RAWG pendientes", e);
        }
        return tareas;
    }

    private static void setupDatabase() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("CREATE TABLE IF NOT EXISTS rawg_details_data (" +
                         "game_id INTEGER PRIMARY KEY, " +
                         "json_full TEXT NOT NULL, " +
                         "fecha_sync TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            
            ResultSet rs = stmt.executeQuery("PRAGMA table_info(rawg_details_data);");
            boolean storesColumnExists = false;
            while (rs.next()) {
                if ("json_stores".equals(rs.getString("name"))) {
                    storesColumnExists = true;
                    break;
                }
            }
            rs.close();

            if (!storesColumnExists) {
                stmt.execute("ALTER TABLE rawg_details_data ADD COLUMN json_stores TEXT;");
            }
            ReleaseDateTrackingStore.ensureSchema(conn);
        } catch (SQLException e) {
            throw new DataStoreException("No se pudo configurar la tabla de detalles RAWG", e);
        }
    }

    private static void ensureParentDir(String filePath) {
        try {
            Path parent = Paths.get(filePath).getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo crear la carpeta para: " + filePath, e);
        }
    }

    // --- DESCARGAS Y GUARDADO ---

    private static void procesarDescargaCompleta(int gameId) {
        String jsonDetalle = descargarDetalleJuego(gameId);

        if ("404".equals(jsonDetalle)) {
            System.err.println("⚠️ ID " + gameId + " no encontrado (404). Marcando error.");
            guardarNuevoCompleto(gameId, "{\"error\":\"404_not_found\"}", "{\"results\":[]}");
            return;
        }
        
        if (jsonDetalle == null) {
             System.err.println("⚠️ Skip ID " + gameId + " (Error API).");
             return;
        }

        String jsonStores = descargarStoresJuego(gameId);
        if (jsonStores == null || "404".equals(jsonStores)) {
            jsonStores = "{\"results\":[]}";
        }
        guardarNuevoCompleto(gameId, jsonDetalle, jsonStores);
    }

    private static String descargarDetalleJuego(int gameId) {
        String urlString = "https://api.rawg.io/api/games/" + gameId + "?key=placeholder";
        return peticionHttpConReintento(urlString);
    }

    private static String descargarStoresJuego(int gameId) {
        String urlString = "https://api.rawg.io/api/games/" + gameId + "/stores?key=placeholder";
        return peticionHttpConReintento(urlString);
    }

    private static void guardarNuevoCompleto(int gameId, String jsonDetail, String jsonStores) {
        CollectorRunGuard.requireCompleteJson(jsonDetail, "detalle RAWG ID " + gameId);
        CollectorRunGuard.requireCompleteJson(jsonStores, "tiendas RAWG ID " + gameId);
        String sql = "INSERT OR REPLACE INTO rawg_details_data(game_id, json_full, json_stores, fecha_sync) VALUES(?,?,?, CURRENT_TIMESTAMP)";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE)) {
            conn.setAutoCommit(false);
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, gameId);
                pstmt.setString(2, jsonDetail);
                pstmt.setString(3, jsonStores);
                pstmt.executeUpdate();
            }
            if (jsonDetail.contains("\"error\":\"404_not_found\"")) {
                ReleaseDateTrackingStore.recordNotFound(conn, gameId, 0);
            } else {
                ReleaseDateTrackingStore.recordCollectorObservation(
                    conn,
                    gameId,
                    ReleaseDatePolicy.fromRawg(jsonDetail)
                );
            }
            conn.commit();
        } catch (SQLException e) {
            throw new DataStoreException("No se pudieron guardar los detalles RAWG de " + gameId, e);
        }
    }

    private static void actualizarStores(int gameId, String jsonStores) {
        CollectorRunGuard.requireCompleteJson(jsonStores, "tiendas RAWG ID " + gameId);
        String sql = "UPDATE rawg_details_data SET json_stores = ?, fecha_sync = CURRENT_TIMESTAMP WHERE game_id = ?";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, jsonStores);
            pstmt.setInt(2, gameId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new DataStoreException("No se pudieron actualizar las tiendas RAWG de " + gameId, e);
        }
    }

    private static void sleepOrStop(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RequestInterruptedException("RAWGDetailCollector interrumpido", e);
        }
    }

    private static String peticionHttpConReintento(String urlString) {
        try {
            return API_CLIENT.get(urlString);
        } catch (HttpStatusException e) {
            if (e.getStatusCode() == 404) return "404";
            throw e;
        }
    }
}
