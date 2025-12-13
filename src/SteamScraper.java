import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SteamScraper {

    private static final String API_KEY = "4BACB9912AA5A10AC6A59248FB48820F";
    private static final String OUTPUT_FILE = "juegos_nuevos.json";
    private static final String PROGRESS_FILE = "progreso.txt";
    private static final String APP_ID_CACHE_FILE = "lista_appids.txt";
    
    private static final int UMBRAL_PARADA_YA_EXISTENTES = 50; 

    public static void main(String[] args) {
        System.out.println("🚀 Iniciando escáner MAESTRO de Steam (Modo Inteligente)...");

        Set<Integer> idsYaDescargados = cargarIdsExistentes();
        System.out.println("📚 Conocemos " + idsYaDescargados.size() + " juegos guardados previamente.");

        boolean modoPrueba = false;
        int limitePrueba = 300;
        
        int ultimoIdProcesado = leerProgreso();
        if (ultimoIdProcesado == 0) {
             File cache = new File(APP_ID_CACHE_FILE);
             if (cache.exists()) {
                 System.out.println("🧹 Limpieza: Borrando caché antigua para buscar lanzamientos recientes...");
                 cache.delete();
             }
        }

        List<Integer> appIds = obtenerListaApps(modoPrueba);
        if (appIds.isEmpty()) return;

        System.out.println("📦 Catálogo total en Steam: " + appIds.size());

        prepararArchivoSalida();

        int indiceDeInicio = appIds.size() - 1;
        
        if (ultimoIdProcesado > 0) {
            System.out.println("🔄 Reanudando sesión anterior desde AppID: " + ultimoIdProcesado);
            int puntoDeReanudacion = -1;
            for(int i = 0; i < appIds.size(); i++){
                if(appIds.get(i) == ultimoIdProcesado){
                    puntoDeReanudacion = i;
                    break;
                }
            }

            if (puntoDeReanudacion != -1) {
                indiceDeInicio = puntoDeReanudacion - 1;
            } else {
                System.out.println("   -> ID de reanudación no encontrado. Empezando desde el principio.");
            }
        } else {
            System.out.println("✨ Buscando nuevos lanzamientos...");
        }

        int contador = 0;
        int seguidosYaExistentes = 0;
        boolean seDetuvoPorUmbral = false;

        for (int i = indiceDeInicio; i >= 0; i--) {
            int appId = appIds.get(i);
            
            if (idsYaDescargados.contains(appId)) {
                seguidosYaExistentes++;
                if (seguidosYaExistentes % 10 == 0) System.out.print(".");
                
                if (seguidosYaExistentes >= UMBRAL_PARADA_YA_EXISTENTES) {
                    System.out.println("\n🛑 ¡ALTO! Se han detectado " + UMBRAL_PARADA_YA_EXISTENTES + " juegos seguidos que ya tienes.");
                    System.out.println("   -> Se asume que la base de datos está actualizada.");
                    seDetuvoPorUmbral = true; // Marcamos que paramos por esta razón
                    break;
                }
                continue;
            }

            if (seguidosYaExistentes > 0) System.out.println();
            seguidosYaExistentes = 0;

            try {
                if (modoPrueba && contador >= limitePrueba) break;

                String jsonJuego = analizarJuego(appId);

                if (jsonJuego != null) {
                    guardarJuegoIncremental(jsonJuego);
                    guardarProgreso(appId); 
                    System.out.println(String.format("✅ NUEVO: ID %d | Restantes: %d", appId, i));
                }
                contador++;
            } catch (Throwable t) {
                System.err.println("❌ Error en AppID " + appId + ": " + t.toString());
            }
        }
        
        // --- LÓGICA DE LIMPIEZA FINAL ---
        cerrarArchivoJson();
        
        // Si el bucle terminó (ya sea por completar la lista o por el umbral),
        // significa que la ejecución fue "exitosa" y no un crash.
        // Por lo tanto, borramos el archivo de progreso para la próxima vez.
        File progreso = new File(PROGRESS_FILE);
        if (progreso.exists()) {
            progreso.delete();
            System.out.println("🧹 Limpieza finalizada: Se ha borrado el archivo de progreso.");
        }
        
        if (seDetuvoPorUmbral) {
             System.out.println("\n🏁 Proceso de actualización finalizado.");
        } else {
             System.out.println("\n🏁 Proceso de escaneo completo finalizado.");
        }
    }

    private static Set<Integer> cargarIdsExistentes() {
        Set<Integer> ids = new HashSet<>();
        File f = new File(OUTPUT_FILE);
        if (!f.exists()) return ids;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(Files.newInputStream(f.toPath()), StandardCharsets.UTF_8))) {
            String line;
            Pattern p = Pattern.compile("\"id\":\\s*(\\d+)");
            while ((line = br.readLine()) != null) {
                Matcher m = p.matcher(line);
                if (m.find()) {
                    ids.add(Integer.parseInt(m.group(1)));
                }
            }
        } catch (IOException e) {
            System.out.println("⚠️ No se pudo leer la base de datos existente: " + e.getMessage());
        }
        return ids;
    }
    
    private static void prepararArchivoSalida() {
        File f = new File(OUTPUT_FILE);
        if (!f.exists()) {
            try (FileWriter w = new FileWriter(f)) {
                w.write("[\n");
            } catch (IOException e) {
                System.out.println("❌ Error creando archivo: " + e.getMessage());
            }
        } else {
            try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                long length = raf.length();
                if (length > 2) { 
                    long pos = length - 1;
                    while (pos > 0) {
                        raf.seek(pos);
                        byte b = raf.readByte();
                        if (b == ']') {
                            raf.setLength(pos); 
                            break;
                        } else if (b != '\n' && b != '\r' && b != ' ') {
                            break;
                        }
                        pos--;
                    }
                }
            } catch (IOException e) {
                System.out.println("⚠️ Advertencia al preparar archivo: " + e.getMessage());
            }
        }
    }

    private static void guardarJuegoIncremental(String jsonJuego) {
        try (FileWriter w = new FileWriter(OUTPUT_FILE, true)) { 
            File f = new File(OUTPUT_FILE);
            if (f.length() > 3) { 
                w.write(",\n");
            }
            w.write(jsonJuego);
        } catch (IOException e) {
            System.out.println("❌ Error guardando juego: " + e.getMessage());
        }
    }
    
    private static void cerrarArchivoJson() {
        try (FileWriter w = new FileWriter(OUTPUT_FILE, true)) {
            w.write("\n]");
        } catch (IOException e) {}
    }

    private static int leerProgreso() {
        try {
            File f = new File(PROGRESS_FILE);
            if (f.exists()) {
                byte[] bytes = Files.readAllBytes(Paths.get(PROGRESS_FILE));
                String content = new String(bytes, StandardCharsets.UTF_8).trim();
                if (content.isEmpty()) return 0;
                return Integer.parseInt(content);
            }
        } catch (Exception e) {
            return 0;
        }
        return 0;
    }

    private static void guardarProgreso(int appId) {
        try (FileWriter w = new FileWriter(PROGRESS_FILE)) {
            w.write(String.valueOf(appId));
        } catch (IOException e) {}
    }

    private static String analizarJuego(int appId) {
        String urlString = "https://store.steampowered.com/api/appdetails?appids=" + appId;

        try {
            String json = peticionHttp(urlString);
            if (json == null || !json.contains("\"success\":true")) return null;
            if (!json.contains("\"type\":\"game\"")) return null;

            Map<String, List<String>> idiomas = procesarIdiomas(json);
            List<String> voces = idiomas.get("voces");
            List<String> textos = idiomas.get("textos");

            String nombre = extraerValorJsonManual(json, "name");
            String imagen = extraerValorJsonManual(json, "header_image");
            String fecha = extraerFechaManual(json); 
            String tamano = extraerTamano(json);
            String urlTienda = "https://store.steampowered.com/app/" + appId;

            StringBuilder sb = new StringBuilder();
            sb.append("  {\n");
            sb.append("    \"id\": ").append(appId).append(",\n");
            sb.append("    \"titulo\": \"").append(limpiarTexto(nombre)).append("\",\n");
            sb.append("    \"fecha\": \"").append(fecha).append("\",\n");
            sb.append("    \"size\": \"").append(tamano).append("\",\n");
            sb.append("    \"url_steam\": \"").append(urlTienda).append("\",\n");
            sb.append("    \"img\": \"").append(imagen).append("\",\n");
            sb.append("    \"supported_languages_raw\": \"").append(limpiarTexto(extraerValorJsonManual(json, "supported_languages"))).append("\",\n");
            
            sb.append("    \"idiomas_texto\": ").append(listaAJson(textos)).append(",\n");
            sb.append("    \"idiomas_voces\": ").append(listaAJson(voces)).append("\n");
            sb.append("  }");
            return sb.toString();

        } catch (Exception e) {
            System.out.println("Error analizando ID " + appId + ": " + e.getMessage());
            return null;
        }
    }

    private static String listaAJson(List<String> lista) {
        StringBuilder sb = new StringBuilder("[");
        for (int k = 0; k < lista.size(); k++) {
            sb.append("\"").append(limpiarTexto(lista.get(k))).append("\"");
            if (k < lista.size() - 1) sb.append(", ");
        }
        sb.append("]");
        return sb.toString();
    }
    
    private static String extraerFechaManual(String json) {
        String key = "\"date\"";
        int idx = json.indexOf(key);
        if (idx == -1) return "TBA";
        int startQuote = json.indexOf("\"", idx + key.length());
        while (startQuote != -1 && json.charAt(startQuote-1) != ':' && json.charAt(startQuote-1) != ' ' && json.charAt(startQuote-1) != '\t') {
             startQuote = json.indexOf("\"", startQuote + 1);
        }
        if (startQuote == -1) return "TBA";
        int endQuote = json.indexOf("\"", startQuote + 1);
        if (endQuote == -1) return "TBA";
        return json.substring(startQuote + 1, endQuote);
    }

    private static Map<String, List<String>> procesarIdiomas(String json) {
        Map<String, List<String>> result = new HashMap<>();
        List<String> voces = new ArrayList<>();
        List<String> textos = new ArrayList<>();
        result.put("voces", voces);
        result.put("textos", textos);

        String rawLangs = extraerValorJsonManual(json, "supported_languages");
        if (rawLangs == null || rawLangs.isEmpty()) return result;

        String cleanLangs = rawLangs.replaceAll("<br>.*", "").trim();
        String[] langParts = cleanLangs.split(",");

        for (String part : langParts) {
            String trimmedPart = part.trim();
            if (trimmedPart.isEmpty()) continue;
            boolean hasVoice = trimmedPart.contains("<strong>*");
            String langName = trimmedPart.replaceAll("<[^>]*>", "").replace("*", "").trim();
            if (!langName.isEmpty()) {
                textos.add(langName);
                if (hasVoice) voces.add(langName);
            }
        }
        return result;
    }

    private static String extraerTamano(String json) {
        try {
            Pattern pSection = Pattern.compile("Storage:.*?(\\d+\\.?\\d*)\\s*(GB|MB)");
            Matcher m = pSection.matcher(json);
            if (m.find()) return m.group(1) + " " + m.group(2);
        } catch (Exception e) {}
        return "N/A";
    }

    private static List<Integer> obtenerListaApps(boolean modoPrueba) {
        File cacheFile = new File(APP_ID_CACHE_FILE);
        if (cacheFile.exists()) {
            System.out.println("✅ Cargando lista de juegos desde caché local...");
            try {
                List<String> lines = Files.readAllLines(Paths.get(APP_ID_CACHE_FILE));
                return lines.stream().map(Integer::parseInt).collect(Collectors.toList());
            } catch (Exception e) {
                System.out.println("⚠️ Caché corrupto. Se descargará de nuevo.");
            }
        }

        System.out.println("ℹ️ Descargando lista de API Steam...");
        List<Integer> ids = new ArrayList<>();
        int lastAppId = 0;
        
        while (true) { 
            try {
                String url = "https://api.steampowered.com/IStoreService/GetAppList/v1/?key=" + API_KEY +
                             "&include_games=true&include_dlc=false&max_results=50000&last_appid=" + lastAppId;
                String json = peticionHttp(url);
                if (json == null || json.isEmpty()) break;
                
                Pattern p = Pattern.compile("\"appid\":(\\d+)");
                Matcher m = p.matcher(json);
                int foundInPage = 0;
                while (m.find()) {
                    ids.add(Integer.parseInt(m.group(1)));
                    foundInPage++;
                }
                
                System.out.println("   -> Obtenidos " + foundInPage + " juegos. Total: " + ids.size());
                
                if (foundInPage == 0) break;
                if (modoPrueba) break;
                
                lastAppId = ids.get(ids.size() - 1);
            } catch (Exception e) {
                break;
            }
        }
        
        if (!ids.isEmpty()) {
            try (FileWriter w = new FileWriter(APP_ID_CACHE_FILE)) {
                for (Integer id : ids) w.write(id + "\n");
                System.out.println("💾 Lista guardada en caché.");
            } catch (IOException e) {}
        }
        return ids;
    }

    private static String peticionHttp(String urlString) throws Exception {
        long backoff = 5000;
        while (true) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(urlString).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                
                int code = conn.getResponseCode();
                if (code == 200) {
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                        StringBuilder content = new StringBuilder();
                        String line;
                        while ((line = in.readLine()) != null) content.append(line);
                        return content.toString();
                    }
                } else if (code == 429 || code >= 500) {
                    System.out.println("⏳ Esperando " + (backoff/1000) + "s (Error " + code + ")");
                    Thread.sleep(backoff);
                    backoff = Math.min(backoff * 2, 60000);
                } else {
                    return null;
                }
            } catch (Exception e) {
                System.out.println("⚠️ Error red: " + e.getMessage() + ". Reintentando...");
                Thread.sleep(5000);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }

    private static String extraerValorJsonManual(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int startKeyIndex = json.indexOf(searchKey);
        if (startKeyIndex == -1) return null;

        int startQuote = json.indexOf("\"", startKeyIndex + searchKey.length());
        boolean colonFound = false;
        for (int i = startKeyIndex + searchKey.length(); i < startQuote; i++) {
            if (json.charAt(i) == ':') {
                colonFound = true;
                break;
            }
        }
        
        if (!colonFound || startQuote == -1) return null;

        StringBuilder sb = new StringBuilder();
        boolean isEscaped = false;
        
        for (int i = startQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (isEscaped) {
                sb.append('\\'); 
                sb.append(c);
                isEscaped = false;
            } else {
                if (c == '\\') {
                    isEscaped = true;
                } else if (c == '"') {
                    return sb.toString();
                } else {
                    sb.append(c);
                }
            }
        }
        return null;
    }

    private static String limpiarTexto(String t) {
        return (t == null) ? "" : t.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}