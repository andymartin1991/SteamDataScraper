import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RAWGRawCollector {

    // ¡¡¡IMPORTANTE: CAMBIA ESTA CLAVE SI TE DA ERROR 401!!!
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
    
    private static final int UMBRAL_PARADA_TEMPRANA = 1000; 

    public static void main(String[] args) {
        try {
            System.out.println("🚀 Iniciando RAWGRawCollector (MODO TOTAL: Todo el catálogo)...");

            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                System.err.println("❌ ERROR CRÍTICO: No se encontró el driver JDBC de SQLite.");
                return;
            }

            setupDatabase();

            Map<Integer, String> juegosYaProcesados = cargarJuegosYaProcesados();
            int totalEnBD = juegosYaProcesados.size();
            System.out.println("📚 Base de datos: " + totalEnBD + " juegos ya registrados.");
            
            long totalApiEstimado = calcularTotalApi();
            if (totalApiEstimado > 0) {
                System.out.println("📊 Total REAL en API (calculado): " + totalApiEstimado + " juegos.");
            }
            
            if (totalEnBD < (totalApiEstimado * 0.98)) { // Aumentamos umbral al 98%
                System.out.println("🚨 MODO LLENADO MASIVO (DECENAL): Se descargará por DECENAS para capturar el 100% del catálogo.");
                descargarPorDecenas(juegosYaProcesados, totalEnBD, totalApiEstimado);
            } else {
                System.out.println("✅ MODO MANTENIMIENTO: Se descargarán las últimas actualizaciones.");
                descargarRecientes(juegosYaProcesados);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- FASE DE CONTEO ---
    private static long calcularTotalApi() {
        System.out.println("🔍 Calculando el número total de juegos en la API (esto puede tardar ~30-40 mins la primera vez)...");
        long total = 0;
        int anioActual = LocalDate.now().getYear();

        for (int anio = anioActual; anio >= 1970; anio--) {
            for (int mes = 12; mes >= 1; mes--) {
                if (anio == anioActual && mes > LocalDate.now().getMonthValue()) continue;
                for (int decena = 3; decena >= 1; decena--) {
                    System.out.printf("\r   -> Contando: %04d-%02d-%d", anio, mes, decena);
                    total += obtenerCountDePeriodo(anio, mes, decena);
                }
            }
        }
        System.out.println(); // Salto de línea final
        return total;
    }

    // --- ESTRATEGIA 1: MANTENIMIENTO (Recientes) ---
    private static void descargarRecientes(Map<Integer, String> juegosProcesados) {
        // ... (sin cambios)
    }

    // --- ESTRATEGIA 2: LLENADO MASIVO (Por Decenas) ---
    private static void descargarPorDecenas(Map<Integer, String> juegosProcesados, int totalEnBD, long totalApi) {
        int anioActual = LocalDate.now().getYear();
        Map<String, Integer> progresoDecenal = cargarProgresoDecenal();
        int totalGuardadosSesion = 0;
        int totalEnBDAhora = totalEnBD;

        for (int anio = anioActual; anio >= 1970; anio--) {
            for (int mes = 12; mes >= 1; mes--) {
                if (anio == anioActual && mes > LocalDate.now().getMonthValue()) continue;
                for (int decena = 3; decena >= 1; decena--) {
                    String decenaId = String.format("%04d-%02d-%d", anio, mes, decena);
                    
                    if (progresoDecenal.getOrDefault(decenaId, 0) == 9999) {
                        System.out.println("⏩ Decena " + decenaId + " ya completada. Saltando...");
                        continue;
                    }

                    System.out.println("\n📅 Procesando DECENA: " + decenaId + "...");
                    int guardadosEnDecena = descargarPeriodoEspecifico(anio, mes, decena, juegosProcesados, progresoDecenal);
                    
                    if (guardadosEnDecena == -1) {
                        System.out.println("🛑 Abortando proceso por error crítico.");
                        return;
                    }
                    
                    totalGuardadosSesion += guardadosEnDecena;
                    totalEnBDAhora += guardadosEnDecena;
                    
                    marcarProgresoDecenal(decenaId, 9999);
                    double porcentaje = totalApi > 0 ? ((double)totalEnBDAhora / totalApi) * 100.0 : 0;
                    System.out.printf("✅ Decena %s completada. (Sesión: +%d | Total BD: %d / %d | %.2f%%)%n", 
                                      decenaId, totalGuardadosSesion, totalEnBDAhora, totalApi, porcentaje);
                }
            }
        }
    }

    private static int descargarPeriodoEspecifico(int anio, int mes, int decena, Map<Integer, String> juegosProcesados, Map<String, Integer> progreso) {
        String decenaId = String.format("%04d-%02d-%d", anio, mes, decena);
        int page = progreso.getOrDefault(decenaId, 0) + 1;
        boolean hayMasDatos = true;
        int guardadosEnPeriodo = 0;
        
        YearMonth yearMonth = YearMonth.of(anio, mes);
        String fechaInicio, fechaFin;

        if (decena == 1) {
            fechaInicio = yearMonth.atDay(1).toString();
            fechaFin = yearMonth.atDay(10).toString();
        } else if (decena == 2) {
            fechaInicio = yearMonth.atDay(11).toString();
            fechaFin = yearMonth.atDay(20).toString();
        } else {
            fechaInicio = yearMonth.atDay(21).toString();
            fechaFin = yearMonth.atEndOfMonth().toString();
        }
        String fechas = fechaInicio + "," + fechaFin;

        while (hayMasDatos) {
            try {
                String urlString = "https://api.rawg.io/api/games?key=" + getApiKey() + 
                                   "&dates=" + fechas + 
                                   "&ordering=-added" + 
                                   "&page_size=40&page=" + page;

                String jsonResponse = peticionHttpConReintentoInfinito(urlString);
                
                if (jsonResponse == null) return guardadosEnPeriodo;

                String resultsArray = extraerArrayResults(jsonResponse);
                if (resultsArray == null || resultsArray.isEmpty() || resultsArray.equals("[]")) return guardadosEnPeriodo;

                List<String> juegosJson = separarObjetosJson(resultsArray);
                
                for (String juegoJson : juegosJson) {
                    if (procesarJuegoIndividual(juegoJson, juegosProcesados)) {
                        guardadosEnPeriodo++;
                    }
                }

                System.out.printf("\r   -> %s | Pág %d | Guardados (Periodo): %d", decenaId, page, guardadosEnPeriodo);
                
                marcarProgresoDecenal(decenaId, page);

                if (!jsonResponse.contains("\"next\":\"http")) hayMasDatos = false;
                page++;
                Thread.sleep(1000); 

            } catch (Exception e) {
                System.err.println("❌ Error en " + decenaId + ": " + e.getMessage());
                return -1; 
            }
        }
        System.out.println(); 
        return guardadosEnPeriodo; 
    }
    
    // --- LÓGICA COMÚN DE PROCESAMIENTO ---
    private static boolean procesarJuegoIndividual(String juegoJson, Map<Integer, String> juegosProcesados) {
        int gameId = extraerIdDelJuego(juegoJson);
        String fechaUpdateNueva = extraerFechaUpdate(juegoJson);
        
        if (gameId != -1) {
            boolean esNuevo = !juegosProcesados.containsKey(gameId);
            boolean esActualizacion = false;

            if (!esNuevo) {
                String fechaUpdateGuardada = juegosProcesados.get(gameId);
                if (fechaUpdateNueva != null && !fechaUpdateNueva.equals(fechaUpdateGuardada)) {
                    esActualizacion = true;
                }
            }

            if (esNuevo || esActualizacion) {
                guardarJuego(gameId, juegoJson);
                juegosProcesados.put(gameId, fechaUpdateNueva);
                return true;
            }
        }
        return false;
    }

    // --- MÉTODOS AUXILIARES ---
    
    private static int extraerCount(String json) {
        Pattern p = Pattern.compile("\"count\":(\\d+)");
        Matcher m = p.matcher(json);
        if (m.find()) return Integer.parseInt(m.group(1));
        return 0;
    }
    
    private static int obtenerCountDePeriodo(int anio, int mes, int decena) {
        YearMonth yearMonth = YearMonth.of(anio, mes);
        String fechaInicio, fechaFin;

        if (decena == 1) {
            fechaInicio = yearMonth.atDay(1).toString();
            fechaFin = yearMonth.atDay(10).toString();
        } else if (decena == 2) {
            fechaInicio = yearMonth.atDay(11).toString();
            fechaFin = yearMonth.atDay(20).toString();
        } else {
            fechaInicio = yearMonth.atDay(21).toString();
            fechaFin = yearMonth.atEndOfMonth().toString();
        }
        String fechas = fechaInicio + "," + fechaFin;
        String urlString = "https://api.rawg.io/api/games?key=" + getApiKey() + "&dates=" + fechas + "&page_size=1";
        
        try {
            String jsonResponse = peticionHttpConReintentoInfinito(urlString);
            if (jsonResponse != null) {
                return extraerCount(jsonResponse);
            }
        } catch (Exception e) {}
        return 0;
    }
    
    private static String extraerArrayResults(String fullJson) {
        int startIndex = fullJson.indexOf("\"results\":[");
        if (startIndex == -1) return null;
        startIndex += "\"results\":".length();
        int balance = 0;
        for (int i = startIndex; i < fullJson.length(); i++) {
            char c = fullJson.charAt(i);
            if (c == '[') balance++;
            if (c == ']') {
                balance--;
                if (balance == 0) return fullJson.substring(startIndex, i + 1);
            }
        }
        return null;
    }

    private static List<String> separarObjetosJson(String jsonArray) {
        List<String> lista = new ArrayList<>();
        if (jsonArray == null || !jsonArray.startsWith("[")) return lista;
        String content = jsonArray.trim().substring(1, jsonArray.length() - 1);
        int balance = 0;
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') balance++;
            if (c == '}') balance--;
            buffer.append(c);
            if (balance == 0 && c == '}') {
                lista.add(buffer.toString());
                buffer.setLength(0);
            } else if (balance == 0 && c == ',') {
                if (buffer.length() > 0 && buffer.charAt(0) == ',') buffer.setLength(0); 
            }
        }
        return lista;
    }

    private static int extraerIdDelJuego(String json) {
        Pattern pContext = Pattern.compile("\"updated\":\"[^\"]+\",\"id\":(\\d+)");
        Matcher mContext = pContext.matcher(json);
        if (mContext.find()) return Integer.parseInt(mContext.group(1));
        
        Pattern p = Pattern.compile("\"id\":(\\d+)");
        Matcher m = p.matcher(json);
        if (m.find()) return Integer.parseInt(m.group(1));
        
        return -1;
    }
    
    private static String extraerFechaUpdate(String json) {
        Pattern p = Pattern.compile("\"updated\":\"([^\"]+)\"");
        Matcher m = p.matcher(json);
        if (m.find()) return m.group(1);
        return null;
    }

    private static void setupDatabase() {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA busy_timeout=5000;");
            stmt.execute("CREATE TABLE IF NOT EXISTS rawg_raw_data (" +
                         "game_id INTEGER PRIMARY KEY, " +
                         "json_data TEXT NOT NULL, " +
                         "fecha_sync TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            stmt.execute("CREATE TABLE IF NOT EXISTS rawg_ignored_ids (" +
                         "game_id INTEGER PRIMARY KEY)");
            // Nueva tabla para guardar el progreso DECENAL
            stmt.execute("CREATE TABLE IF NOT EXISTS rawg_progress_decenal (" +
                         "decena_id TEXT PRIMARY KEY, " + // Formato "YYYY-MM-1", "YYYY-MM-2", "YYYY-MM-3"
                         "ultima_pagina INTEGER NOT NULL)");
        } catch (Exception e) {
            System.err.println("❌ Error fatal DB: " + e.getMessage());
            System.exit(1);
        }
    }

    private static Map<String, Integer> cargarProgresoDecenal() {
        Map<String, Integer> progreso = new HashMap<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT decena_id, ultima_pagina FROM rawg_progress_decenal");
            while (rs.next()) {
                progreso.put(rs.getString("decena_id"), rs.getInt("ultima_pagina"));
            }
        } catch (Exception e) {}
        return progreso;
    }

    private static void marcarProgresoDecenal(String decenaId, int pagina) {
        String sql = "INSERT OR REPLACE INTO rawg_progress_decenal(decena_id, ultima_pagina) VALUES(?,?)";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, decenaId);
            pstmt.setInt(2, pagina);
            pstmt.executeUpdate();
        } catch (Exception e) {
            System.err.println("⚠️ Error guardando progreso de la decena " + decenaId + ": " + e.getMessage());
        }
    }

    private static Map<Integer, String> cargarJuegosYaProcesados() {
        Map<Integer, String> juegos = new HashMap<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             Statement stmt = conn.createStatement()) {
            ResultSet rsIgnored = stmt.executeQuery("SELECT game_id FROM rawg_ignored_ids");
            while (rsIgnored.next()) juegos.put(rsIgnored.getInt("game_id"), "IGNORED");
            
            ResultSet rsGames = stmt.executeQuery("SELECT game_id, json_data FROM rawg_raw_data");
            while (rsGames.next()) {
                int id = rsGames.getInt("game_id");
                String json = rsGames.getString("json_data");
                String fechaUpdate = extraerFechaUpdate(json);
                if (fechaUpdate == null) fechaUpdate = "1970-01-01T00:00:00";
                juegos.put(id, fechaUpdate);
            }
        } catch (Exception e) {}
        return juegos;
    }

    private static void guardarJuego(int gameId, String json) {
        String sql = "INSERT OR REPLACE INTO rawg_raw_data(game_id, json_data) VALUES(?,?)";
        int intentos = 0;
        while (intentos < 3) {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, gameId);
                pstmt.setString(2, json);
                pstmt.executeUpdate();
                return;
            } catch (Exception e) {
                if (e.getMessage().contains("locked")) {
                    intentos++;
                    try { Thread.sleep(100); } catch (InterruptedException ie) {}
                } else {
                    System.err.println("⚠️ Error guardando juego " + gameId + ": " + e.getMessage());
                    return;
                }
            }
        }
    }
    
    private static String peticionHttpConReintentoInfinito(String urlString) {
        int intentos = 0;
        
        while (true) { 
            try {
                return peticionHttp(urlString);
            } catch (Exception e) {
                if (e.getMessage().contains("401")) {
                    System.err.println("⚠️ Error 401 (Unauthorized). Rotando API Key...");
                    rotateApiKey();
                    // Reconstruimos la URL con la nueva clave
                    urlString = urlString.replaceAll("key=[^&]+", "key=" + getApiKey());
                    continue; // Reintentamos inmediatamente con la nueva clave
                }
                if (e.getMessage().contains("404")) {
                    return null; 
                }

                intentos++;
                System.err.println("⚠️ Error HTTP (Intento " + intentos + "): " + e.getMessage());
                
                if (e.getMessage().contains("502") || e.getMessage().contains("500") || e.getMessage().contains("504") || e.getMessage().contains("429")) {
                    System.out.println("⏳ Servidor saturado o Rate Limit. Esperando 60s y reintentando...");
                    try { Thread.sleep(60000); } catch (InterruptedException ie) {}
                } else {
                    System.out.println("⏳ Error de conexión. Esperando 10s...");
                    try { Thread.sleep(10000); } catch (InterruptedException ie) {}
                }
            }
        }
    }

    private static String peticionHttp(String urlString) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "SteamDataScraper/1.0");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            if (code == 200) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"))) {
                    StringBuilder content = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) content.append(line);
                    return content.toString();
                }
            } else {
                throw new Exception("HTTP Code " + code);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}