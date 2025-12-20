import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class RAWGDetailCollector {

    private static final String API_KEY = "867e9e7e82c3459593e684c2664243bd";
    private static final String DB_FILE = "rawg_raw.sqlite";
    
    // Configuración: Días a esperar antes de volver a comprobar un juego incompleto
    private static final int DIAS_COOLDOWN_VACIOS = 3;

    private static class GameTask {
        int id;
        boolean tieneDetalle;
        boolean esError404;
        boolean esReintentoVacio; // Stores vacíos
        boolean descripcionVacia; // Descripción vacía

        public GameTask(int id, boolean tieneDetalle, boolean esError404, boolean esReintentoVacio, boolean descripcionVacia) {
            this.id = id;
            this.tieneDetalle = tieneDetalle;
            this.esError404 = esError404;
            this.esReintentoVacio = esReintentoVacio;
            this.descripcionVacia = descripcionVacia;
        }
    }

    public static void main(String[] args) {
        try {
            System.out.println("🚀 Iniciando RAWG Detail Collector (Modo Inteligente con Cooldown)...");

            Class.forName("org.sqlite.JDBC");
            setupDatabase();

            List<GameTask> pendientes = obtenerTareasPendientes();
            System.out.println("📋 Juegos pendientes de procesar: " + pendientes.size());

            int procesados = 0;
            for (GameTask tarea : pendientes) {
                try {
                    if (tarea.esError404) continue;

                    // CASO: Ya tenemos detalle previo
                    if (tarea.tieneDetalle) {
                        
                        // SUB-CASO: Descripción vacía -> FORZAR REDESCARGA COMPLETA
                        if (tarea.descripcionVacia) {
                            System.out.println("🔄 [" + procesados + "/" + pendientes.size() + "] ID " + tarea.id + ": Descripción vacía. Reintentando descarga completa...");
                            procesarDescargaCompleta(tarea.id);
                            procesados++;
                            Thread.sleep(600);
                            continue; 
                        }

                        // SUB-CASO: Solo faltan Stores (o reintento de stores vacíos)
                        String motivo = tarea.esReintentoVacio ? "Reintento Stores (Vacío hace >" + DIAS_COOLDOWN_VACIOS + "d)" : "Nuevo Stores";
                        String jsonStores = descargarStoresJuego(tarea.id);
                        
                        if (jsonStores == null || "404".equals(jsonStores)) {
                            jsonStores = "{\"results\":[]}";
                        }

                        actualizarStores(tarea.id, jsonStores);
                        procesados++;
                        
                        if (jsonStores.contains("\"results\":[]") || jsonStores.equals("[]")) {
                            System.out.println("⚠️ [" + procesados + "/" + pendientes.size() + "] ID " + tarea.id + ": Stores siguen vacíos. Se reintentará en " + DIAS_COOLDOWN_VACIOS + " días.");
                        } else {
                            System.out.println("✅ [" + procesados + "/" + pendientes.size() + "] Stores actualizados (" + motivo + "): ID " + tarea.id);
                        }
                        
                        Thread.sleep(600); 
                    } 
                    // CASO: Juego totalmente nuevo (sin detalle previo)
                    else {
                        procesarDescargaCompleta(tarea.id);
                        procesados++;
                        System.out.println("✅ [" + procesados + "/" + pendientes.size() + "] Nuevo Detalle y Stores descargados: ID " + tarea.id);
                        Thread.sleep(600); 
                    }

                } catch (Exception e) {
                    System.err.println("❌ Error en ID " + tarea.id + ": " + e.getMessage());
                }
            }
            
            System.out.println("🏁 Proceso finalizado.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void procesarDescargaCompleta(int gameId) {
        String jsonDetalle = descargarDetalleJuego(gameId);

        if ("404".equals(jsonDetalle)) {
            System.err.println("⚠️ ID " + gameId + " no encontrado (404). Marcando error.");
            guardarNuevoCompleto(gameId, "{\"error\":\"404_not_found\"}", "{\"results\":[]}");
            return;
        }

        if (jsonDetalle != null) {
            String jsonStores = descargarStoresJuego(gameId);
            if (jsonStores == null || "404".equals(jsonStores)) {
                jsonStores = "{\"results\":[]}";
            }
            guardarNuevoCompleto(gameId, jsonDetalle, jsonStores);
        } else {
            System.err.println("⚠️ No se pudo bajar detalle para ID " + gameId);
        }
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
                System.out.println("ℹ️ Columna 'json_stores' añadida.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static List<GameTask> obtenerTareasPendientes() {
        List<GameTask> tareas = new ArrayList<>();
        
        String sql = "SELECT r.game_id, " +
                     "d.json_full, d.json_stores, d.fecha_sync, " +
                     "CASE WHEN d.json_full IS NOT NULL THEN 1 ELSE 0 END as tiene_detalle, " +
                     "CASE WHEN d.json_full LIKE '%\"error\":\"404_not_found\"%' THEN 1 ELSE 0 END as es_error " +
                     "FROM rawg_raw_data r " +
                     "LEFT JOIN rawg_details_data d ON r.game_id = d.game_id " +
                     "WHERE " +
                     // Caso 1: Nuevo total
                     "d.game_id IS NULL " +
                     // Caso 2: Falta store
                     "OR d.json_stores IS NULL " +
                     // Caso 3: Store vacío Y fecha antigua (Cooldown)
                     "OR ( (d.json_stores LIKE '%\"results\":[]%' OR d.json_stores = '[]') " +
                     "     AND d.fecha_sync < datetime('now', '-" + DIAS_COOLDOWN_VACIOS + " days') )" +
                     // Caso 4: Descripción vacía Y fecha antigua (Cooldown)
                     "OR ( (d.json_full LIKE '%\"description\":\"\"%' OR d.json_full LIKE '%\"description_raw\":\"\"%') " +
                     "     AND d.fecha_sync < datetime('now', '-" + DIAS_COOLDOWN_VACIOS + " days') )";
        
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                if (rs.getBoolean("es_error")) continue;

                String jsonStores = rs.getString("json_stores");
                String jsonFull = rs.getString("json_full");
                
                boolean esVacioStores = (jsonStores != null && (jsonStores.contains("\"results\":[]") || jsonStores.equals("[]")));
                boolean esVacioDesc = (jsonFull != null && (jsonFull.contains("\"description\":\"\"") || jsonFull.contains("\"description_raw\":\"\"")));

                tareas.add(new GameTask(
                    rs.getInt("game_id"),
                    rs.getBoolean("tiene_detalle"),
                    rs.getBoolean("es_error"),
                    esVacioStores,
                    esVacioDesc
                ));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return tareas;
    }

    private static String descargarDetalleJuego(int gameId) {
        String urlString = "https://api.rawg.io/api/games/" + gameId + "?key=" + API_KEY;
        return peticionHttpConReintento(urlString);
    }

    private static String descargarStoresJuego(int gameId) {
        String urlString = "https://api.rawg.io/api/games/" + gameId + "/stores?key=" + API_KEY;
        return peticionHttpConReintento(urlString);
    }

    private static void guardarNuevoCompleto(int gameId, String jsonDetail, String jsonStores) {
        // Al insertar, fecha_sync se pone sola a CURRENT_TIMESTAMP
        String sql = "INSERT OR REPLACE INTO rawg_details_data(game_id, json_full, json_stores, fecha_sync) VALUES(?,?,?, CURRENT_TIMESTAMP)";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, gameId);
            pstmt.setString(2, jsonDetail);
            pstmt.setString(3, jsonStores);
            pstmt.executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void actualizarStores(int gameId, String jsonStores) {
        // IMPORTANTE: Actualizamos fecha_sync para resetear el cooldown
        String sql = "UPDATE rawg_details_data SET json_stores = ?, fecha_sync = CURRENT_TIMESTAMP WHERE game_id = ?";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, jsonStores);
            pstmt.setInt(2, gameId);
            pstmt.executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String peticionHttpConReintento(String urlString) {
        int intentos = 0;
        while (intentos < 3) {
            try {
                return peticionHttp(urlString);
            } catch (Exception e) {
                if (e.getMessage().contains("HTTP 404")) {
                    return "404";
                }
                intentos++;
                System.err.println("⚠️ Error HTTP (Intento " + intentos + "): " + e.getMessage() + " para URL: " + urlString);
                try { Thread.sleep(2000); } catch (InterruptedException ie) {}
            }
        }
        return null;
    }

    private static String peticionHttp(String urlString) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "SteamDataScraper/1.0");
        
        int code = conn.getResponseCode();
        if (code == 200) {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"))) {
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) content.append(line);
                return content.toString();
            }
        } else {
            throw new Exception("HTTP " + code);
        }
    }
}