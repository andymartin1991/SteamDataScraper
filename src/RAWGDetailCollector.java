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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RAWGDetailCollector {

    // GESTOR DE CLAVES API
    private static final String[] API_KEYS = {
        "7eb38d19ab0c46e2908b58186e8accae",
        "c792ba46b7c34c2ab558cdfc0b849aaf",
        "5d43c4de1fb64c10bd71eade75b98996",
        "d178a23d354549e69bdb2267329a7f13",
        "fb26a83a918348e8bb7e231b16dd8a29",
            "d19734a6cf8e4790b728aac15d47eb41",
            "45313bd21d924b078452827d6b69e400",
            "70f2a2ef78244243b2ef71326b98db92",
            "8dca1c2e7b8742b789970bc633b516ed",
            "67ec9adb41164a889f44cc20ef112c24",
            "88a4ab0fd225435da122274bf0e2a975",
            "16395406b89e4a50b4295e177f432c78",
            "cdb2e34c650e468a89cde2d5aa7f1a69",
            "d23bb60d7d3d4f1f87157ebc39a9dd4b"
    };
    private static int currentKeyIndex = 0;

    private static String getApiKey() {
        return API_KEYS[currentKeyIndex];
    }

    private static void rotateApiKey() {
        currentKeyIndex = (currentKeyIndex + 1) % API_KEYS.length;
        System.out.println("🔄 Rotando API Key... Nueva clave: " + getApiKey().substring(0, 8) + "...");
    }

    private static final String DB_FILE = "rawg_raw.sqlite";
    private static final int DIAS_COOLDOWN_VACIOS = 3;
    
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
        try {
            System.out.println("🚀 Iniciando RAWG Detail Collector (Modo Inteligente con Cooldown)...");

            Class.forName("org.sqlite.JDBC");
            setupDatabase();

            // 1. Analizar lo que YA tenemos guardado
            System.out.println("📊 Analizando base de datos existente...");
            Stats statsProcesados = analizarJuegosProcesados();
            
            // 2. Obtener y clasificar lo pendiente
            System.out.println("📋 Obteniendo lista de pendientes...");
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
                            Thread.sleep(1000);
                            continue; 
                        }

                        String motivo = tarea.esReintentoVacio ? "Reintento Stores" : "Nuevo Stores";
                        String jsonStores = descargarStoresJuego(tarea.id);
                        
                        if (jsonStores == null || "404".equals(jsonStores)) jsonStores = "{\"results\":[]}";

                        actualizarStores(tarea.id, jsonStores);
                        procesados++;
                        
                        String tipo = tarea.esSoloPC ? "PC" : "CONSOLA";
                        System.out.println("✅ [" + procesados + "/" + pendientes.size() + "] Stores (" + tipo + "): ID " + tarea.id);
                        
                        Thread.sleep(1000);
                    } else {
                        procesarDescargaCompleta(tarea.id);
                        procesados++;
                        String tipo = tarea.esSoloPC ? "PC" : "CONSOLA";
                        System.out.println("✅ [" + procesados + "/" + pendientes.size() + "] Full (" + tipo + "): ID " + tarea.id);
                        Thread.sleep(1000);
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

    // --- LÓGICA DE CLASIFICACIÓN ---

    private static boolean esSoloPC(String jsonBasic) {
        if (jsonBasic == null) return false; // Ante la duda, priorizar (no es solo PC)
        
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
            
            // Es Solo PC si: Tiene 1 plataforma Y esa es PC
            return (count == 1 && tienePC);
        }
        return false; // Si no tiene plataformas definidas, lo tratamos como prioritario por si acaso
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
        } catch (Exception e) {
            System.err.println("⚠️ Error analizando estadísticas: " + e.getMessage());
        }
        return stats;
    }

    private static List<GameTask> obtenerTareasPendientes() {
        List<GameTask> tareas = new ArrayList<>();
        
        String sql = "SELECT r.game_id, r.json_data, " +
                     "d.json_full, d.json_stores, d.fecha_sync, " +
                     "CASE WHEN d.json_full IS NOT NULL THEN 1 ELSE 0 END as tiene_detalle, " +
                     "CASE WHEN d.json_full LIKE '%\"error\":\"404_not_found\"%' THEN 1 ELSE 0 END as es_error " +
                     "FROM rawg_raw_data r " +
                     "LEFT JOIN rawg_details_data d ON r.game_id = d.game_id " +
                     "WHERE " +
                     "d.game_id IS NULL " +
                     "OR d.json_stores IS NULL " +
                     "OR ( (d.json_stores LIKE '%\"results\":[]%' OR d.json_stores = '[]') " +
                     "     AND d.fecha_sync < datetime('now', '-" + DIAS_COOLDOWN_VACIOS + " days') )" +
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
                    esVacioDesc,
                    esSoloPC(rs.getString("json_data"))
                ));
            }
        } catch (Exception e) {
            e.printStackTrace();
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
        } catch (Exception e) {
            e.printStackTrace();
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
        String urlString = "https://api.rawg.io/api/games/" + gameId + "?key=" + getApiKey();
        return peticionHttpConReintento(urlString);
    }

    private static String descargarStoresJuego(int gameId) {
        String urlString = "https://api.rawg.io/api/games/" + gameId + "/stores?key=" + getApiKey();
        return peticionHttpConReintento(urlString);
    }

    private static void guardarNuevoCompleto(int gameId, String jsonDetail, String jsonStores) {
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
        while (true) {
            try {
                String urlConClaveActual = urlString.replaceAll("key=[^&]+", "key=" + getApiKey());
                return peticionHttp(urlConClaveActual);
            } catch (Exception e) {
                if (e.getMessage().contains("401")) {
                    System.err.println("⚠️ Error 401 (Unauthorized). Rotando API Key...");
                    rotateApiKey();
                    intentos = 0;
                    continue;
                }
                if (e.getMessage().contains("404")) return "404";
                
                intentos++;
                if (intentos > 5) {
                    System.err.println("❌ Abortando " + urlString);
                    return null;
                }
                try { Thread.sleep(2000); } catch (InterruptedException ie) {}
            }
        }
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