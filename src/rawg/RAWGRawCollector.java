package rawg;

import common.DataStoreException;
import common.CollectorRunGuard;
import common.CollectorRunGuard.JsonTarget;
import common.CollectorRunGuard.RunSession;
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
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class RAWGRawCollector {

    private static final String DB_FILE = "data/db/rawg_raw.sqlite";
    private static final RAWGApiClient API_CLIENT = new RAWGApiClient();
    
    private static final int UMBRAL_PARADA_TEMPRANA = 1000; 

    public static void main(String[] args) {
        RunSession runSession = null;
        try {
            System.out.println("🚀 Iniciando RAWGRawCollector (MODO TOTAL: Todo el catálogo)...");

            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("No se encontró el driver JDBC de SQLite", e);
            }

            ensureParentDir(DB_FILE);
            setupDatabase();
            runSession = CollectorRunGuard.begin(
                DB_FILE,
                "RAWGRawCollector",
                new JsonTarget("rawg_raw_data", "game_id", "json_data", "fecha_sync")
            );

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

        } catch (RuntimeException e) {
            throw new IllegalStateException("RAWGRawCollector finalizó con error", e);
        } finally {
            if (runSession != null) runSession.close();
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
        int page = 1;
        int sinCambiosConsecutivos = 0;
        int guardados = 0;

        System.out.println("🔄 Revisando juegos por fecha de actualización...");

        while (sinCambiosConsecutivos < UMBRAL_PARADA_TEMPRANA) {
            String urlString = "https://api.rawg.io/api/games?key=placeholder"
                + "&ordering=-updated&page_size=40&page=" + page;
            String jsonResponse = peticionHttpConReintentoInfinito(urlString);

            if (jsonResponse == null) {
                break;
            }

            String resultsArray = extraerArrayResults(jsonResponse);
            if (resultsArray == null || resultsArray.isEmpty() || resultsArray.equals("[]")) {
                break;
            }

            for (String juegoJson : separarObjetosJson(resultsArray)) {
                if (procesarJuegoIndividual(juegoJson, juegosProcesados)) {
                    guardados++;
                    sinCambiosConsecutivos = 0;
                } else {
                    sinCambiosConsecutivos++;
                }

                if (sinCambiosConsecutivos >= UMBRAL_PARADA_TEMPRANA) {
                    break;
                }
            }

            System.out.printf("\r   -> Pág %d | Nuevos/actualizados: %d | Sin cambios seguidos: %d/%d",
                page, guardados, sinCambiosConsecutivos, UMBRAL_PARADA_TEMPRANA);

            if (!jsonResponse.contains("\"next\":\"http")) {
                break;
            }

            page++;
            sleepOrStop(1_000);
        }

        System.out.println();
        System.out.println("✅ Mantenimiento RAWG finalizado. Nuevos/actualizados: " + guardados);
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
                        throw new IllegalStateException("Error crítico procesando la decena " + decenaId);
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
                String urlString = "https://api.rawg.io/api/games?key=placeholder" +
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
                sleepOrStop(1_000);

            } catch (RuntimeException e) {
                if (e instanceof RequestInterruptedException interrupted) {
                    throw interrupted;
                }
                if (e instanceof DataStoreException persistenceFailure) {
                    throw persistenceFailure;
                }
                if (e instanceof SecurityException securityException) {
                    throw securityException;
                }
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
        String urlString = "https://api.rawg.io/api/games?key=placeholder&dates=" + fechas + "&page_size=1";
        
        String jsonResponse = peticionHttpConReintentoInfinito(urlString);
        return jsonResponse == null ? 0 : extraerCount(jsonResponse);
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
            ReleaseDateTrackingStore.ensureSchema(conn);
        } catch (SQLException e) {
            throw new DataStoreException("No se pudo configurar " + DB_FILE, e);
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

    private static Map<String, Integer> cargarProgresoDecenal() {
        Map<String, Integer> progreso = new HashMap<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT decena_id, ultima_pagina FROM rawg_progress_decenal");
            while (rs.next()) {
                progreso.put(rs.getString("decena_id"), rs.getInt("ultima_pagina"));
            }
        } catch (SQLException e) {
            throw new DataStoreException("No se pudo cargar el progreso decenal de RAWG", e);
        }
        return progreso;
    }

    private static void marcarProgresoDecenal(String decenaId, int pagina) {
        String sql = "INSERT OR REPLACE INTO rawg_progress_decenal(decena_id, ultima_pagina) VALUES(?,?)";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, decenaId);
            pstmt.setInt(2, pagina);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new DataStoreException("No se pudo guardar el progreso de la decena " + decenaId, e);
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
        } catch (SQLException e) {
            throw new DataStoreException("No se pudo cargar el catálogo RAWG ya procesado", e);
        }
        return juegos;
    }
    private static void guardarJuego(int gameId, String json) {
        CollectorRunGuard.requireCompleteJson(json, "RAWG ID " + gameId);
        String sql = "INSERT OR REPLACE INTO rawg_raw_data(game_id, json_data) VALUES(?,?)";
        int intentos = 0;
        while (intentos < 3) {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE)) {
                conn.setAutoCommit(false);
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setInt(1, gameId);
                    pstmt.setString(2, json);
                    pstmt.executeUpdate();
                }
                ReleaseDateTrackingStore.recordCollectorObservation(
                    conn,
                    gameId,
                    ReleaseDatePolicy.fromRawg(json)
                );
                conn.commit();
                return;
            } catch (SQLException e) {
                if (isDatabaseLocked(e)) {
                    intentos++;
                    sleepOrStop(100);
                } else {
                    throw new DataStoreException("No se pudo guardar el juego RAWG " + gameId, e);
                }
            }
        }
        throw new DataStoreException("La base de datos siguió bloqueada al guardar RAWG " + gameId, null);
    }

    private static boolean isDatabaseLocked(SQLException exception) {
        String message = exception.getMessage();
        return message != null && message.toLowerCase().contains("locked");
    }
    
    private static String peticionHttpConReintentoInfinito(String urlString) {
        try {
            return API_CLIENT.get(urlString);
        } catch (common.ResilientHttpClient.HttpStatusException e) {
            if (e.getStatusCode() == 404) return null;
            throw e;
        }
    }

    private static void sleepOrStop(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RequestInterruptedException("RAWGRawCollector interrumpido", e);
        }
    }
}
