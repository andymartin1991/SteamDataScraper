import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

public class RAWGScraper {

    private static final String DB_FILE = "rawg_raw.sqlite";
    private static final String OUTPUT_FILE = "rawg_games.json.gz";

    public static void main(String[] args) {
        try {
            System.out.println("🚀 Iniciando RAWG Scraper (Fusión Lista + Detalles)...");

            Class.forName("org.sqlite.JDBC");

            try (Writer w = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(OUTPUT_FILE)), "UTF-8")) {
                w.write("[\n");
                
                try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
                     Statement stmt = conn.createStatement()) {
                    
                    stmt.setFetchSize(1000); 
                    
                    // JOIN para obtener datos básicos, detalles y stores
                    String sql = "SELECT r.json_data as json_basic, d.json_full as json_detail, d.json_stores " +
                                 "FROM rawg_raw_data r " +
                                 "LEFT JOIN rawg_details_data d ON r.game_id = d.game_id";
                                 
                    ResultSet rs = stmt.executeQuery(sql);
                    
                    int procesados = 0;
                    int exportados = 0;
                    boolean primero = true;

                    while (rs.next()) {
                        String jsonBasic = rs.getString("json_basic");
                        String jsonDetail = rs.getString("json_detail"); // Puede ser null
                        String jsonStores = rs.getString("json_stores"); // Puede ser null
                        
                        String jsonProcesado = procesarJuego(jsonBasic, jsonDetail, jsonStores);
                        
                        if (jsonProcesado != null) {
                            if (!primero) {
                                w.write(",\n");
                            }
                            w.write(jsonProcesado);
                            primero = false;
                            exportados++;
                        }
                        
                        procesados++;
                        if (procesados % 1000 == 0) {
                            System.out.println("⚙️ Procesados: " + procesados + " | Exportados: " + exportados);
                        }
                    }
                    
                    System.out.println("\n✅ Exportación finalizada.");
                    System.out.println("   -> Total leídos: " + procesados);
                    System.out.println("   -> Total exportados: " + exportados);
                    System.out.println("   -> Archivo de salida: " + OUTPUT_FILE);
                }
                
                w.write("\n]");
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String procesarJuego(String jsonBasic, String jsonDetail, String jsonStores) {
        try {
            // Filtro 1: Descartar juegos "To Be Announced"
            if (jsonBasic.contains("\"tba\":true")) return null;
            
            String fechaStr = extraerValorJsonManual(jsonBasic, "released");
            if (fechaStr == null || fechaStr.isEmpty()) return null;

            // Filtro 2: Descartar juegos con fecha de lanzamiento futura
            try {
                LocalDate fechaLanzamiento = LocalDate.parse(fechaStr, DateTimeFormatter.ISO_LOCAL_DATE);
                if (fechaLanzamiento.isAfter(LocalDate.now())) {
                    return null; // El juego es un próximo lanzamiento, lo descartamos
                }
            } catch (Exception e) {
                // Si la fecha tiene un formato raro, lo descartamos por seguridad
                return null;
            }

            String titulo = extraerValorJsonManual(jsonBasic, "name");
            String slug = extraerValorJsonManual(jsonBasic, "slug");
            
            // --- DETERMINAR TIPO (GAME vs DLC) ---
            // Usamos EXCLUSIVAMENTE el campo 'parents_count' del detalle.
            // Si no hay detalle, asumimos 'game' por defecto hasta que se descargue.
            String tipo = "game";
            if (jsonDetail != null && !jsonDetail.isEmpty()) {
                int parentsCount = extraerEnteroJsonManual(jsonDetail, "parents_count");
                if (parentsCount > 0) {
                    tipo = "dlc";
                }
            }

            String imgPrincipal = extraerValorJsonManual(jsonBasic, "background_image");
            int metacritic = extraerMetacritic(jsonBasic);

            List<String> generos = extraerListaDeObjetos(jsonBasic, "genres");
            List<String> tags = extraerListaDeObjetos(jsonBasic, "tags");
            List<String> galeria = extraerGaleria(jsonBasic);
            
            boolean isFree = detectarSiEsGratis(generos, tags);

            Set<String> plataformasSet = new HashSet<>(extraerPlataformas(jsonBasic));
            String tiendasJson = construirTiendasJson(jsonBasic, jsonStores, titulo, plataformasSet, isFree);

            List<String> plataformasFinales = new ArrayList<>(plataformasSet);

            // --- DATOS DEL DETALLE (Si existe) ---
            String descripcion = "";
            if (jsonDetail != null && !jsonDetail.isEmpty()) {
                descripcion = extraerValorJsonManual(jsonDetail, "description_raw");
                if (descripcion == null) {
                    descripcion = extraerValorJsonManual(jsonDetail, "description");
                }
            }
            
            String descripcionCorta = acortarDescripcion(descripcion);
            
            // Filtro 3: Si no hay descripción, NO exportamos el juego (esperamos a que el Collector la consiga)
            if (descripcionCorta.isEmpty()) {
                return null;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("  {\n");
            sb.append("    \"slug\": \"").append(slug).append("\",\n");
            sb.append("    \"titulo\": \"").append(limpiarTexto(titulo)).append("\",\n");
            sb.append("    \"tipo\": \"").append(tipo).append("\",\n");
            sb.append("    \"descripcion_corta\": \"").append(limpiarTexto(descripcionCorta)).append("\",\n"); 
            sb.append("    \"fecha_lanzamiento\": \"").append(fechaStr).append("\",\n");
            sb.append("    \"storage\": \"N/A\",\n"); 
            
            sb.append("    \"generos\": ").append(listaAJson(generos)).append(",\n");
            sb.append("    \"plataformas\": ").append(listaAJson(plataformasFinales)).append(",\n"); 
            sb.append("    \"img_principal\": \"").append(limpiarTexto(imgPrincipal)).append("\",\n");
            sb.append("    \"galeria\": ").append(listaAJson(galeria)).append(",\n");
            
            sb.append("    \"idiomas\": {\n"); 
            sb.append("      \"voces\": [],\n");
            sb.append("      \"textos\": []\n");
            sb.append("    },\n");
            
            sb.append("    \"metacritic\": ").append(metacritic).append(",\n");
            sb.append("    \"tiendas\": ").append(tiendasJson).append("\n");
            sb.append("  }");
            
            return sb.toString();

        } catch (Exception e) {
            return null;
        }
    }
    
    private static boolean detectarSiEsGratis(List<String> generos, List<String> tags) {
        for (String g : generos) {
            if (g.equalsIgnoreCase("Free to Play")) return true;
        }
        for (String t : tags) {
            if (t.equalsIgnoreCase("Free to Play")) return true;
        }
        return false;
    }

    private static int extraerMetacritic(String json) {
        String meta = extraerValorJsonManual(json, "metacritic");
        if (meta == null || meta.equals("null") || meta.isEmpty()) return 0;
        try {
            return Integer.parseInt(meta);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    // Nuevo helper para extraer enteros de forma segura
    private static int extraerEnteroJsonManual(String json, String key) {
        String val = extraerValorJsonManual(json, key);
        if (val == null || val.isEmpty() || val.equals("null")) return 0;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> extraerListaDeObjetos(String json, String key) {
        List<String> resultados = new ArrayList<>();
        String searchKey = "\"" + key + "\":[";
        int startIdx = json.indexOf(searchKey);
        if (startIdx == -1) return resultados;

        int endIdx = json.indexOf("]", startIdx);
        if (endIdx == -1) return resultados;

        String arrayContent = json.substring(startIdx + searchKey.length(), endIdx);
        Pattern p = Pattern.compile("\"name\":\"([^\"]+)\"");
        Matcher m = p.matcher(arrayContent);
        while (m.find()) {
            resultados.add(m.group(1));
        }
        return resultados;
    }
    
    private static List<String> extraerPlataformas(String json) {
        List<String> plataformas = new ArrayList<>();
        String searchKey = "\"platforms\":[";
        int startIdx = json.indexOf(searchKey);
        if (startIdx == -1) return plataformas;
        
        int balance = 0;
        int endIdx = -1;
        for(int i = startIdx + searchKey.length() - 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if(c == '[') balance++;
            if(c == ']') {
                balance--;
                if(balance == 0) {
                    endIdx = i;
                    break;
                }
            }
        }
        
        if (endIdx == -1) return plataformas;
        
        String arrayContent = json.substring(startIdx + searchKey.length(), endIdx);
        Pattern p = Pattern.compile("\"platform\":\\{[^}]*\"name\":\"([^\"]+)\"");
        Matcher m = p.matcher(arrayContent);
        while (m.find()) {
            String plat = m.group(1);
            if (!plat.equalsIgnoreCase("Web") && !plat.equalsIgnoreCase("Android") && !plat.equalsIgnoreCase("iOS")) {
                plataformas.add(plat);
            }
        }
        return plataformas;
    }
    
    private static List<String> extraerGaleria(String json) {
        List<String> galeria = new ArrayList<>();
        String searchKey = "\"short_screenshots\":[";
        int startIdx = json.indexOf(searchKey);
        if (startIdx == -1) return galeria;
        
        int endIdx = json.indexOf("]", startIdx);
        if (endIdx == -1) return galeria;
        
        String arrayContent = json.substring(startIdx + searchKey.length(), endIdx);
        Pattern p = Pattern.compile("\"image\":\"([^\"]+)\"");
        Matcher m = p.matcher(arrayContent);
        int count = 0;
        while (m.find() && count < 3) {
            galeria.add(m.group(1));
            count++;
        }
        return galeria;
    }

    private static String construirTiendasJson(String jsonBasic, String jsonStores, String gameTitle, Set<String> plataformasSet, boolean isFree) {
        // Estrategia MEJORADA:
        // 1. Comprobamos si jsonStores es válido Y tiene contenido real.
        //    Si es "[]" o "{"results":[]}", lo consideramos inválido.
        
        boolean storesValido = false;
        if (jsonStores != null && !jsonStores.isEmpty() && !jsonStores.equals("[]")) {
            if (!jsonStores.contains("\"results\":[]")) {
                storesValido = true;
            }
        }

        if (storesValido) {
            return construirTiendasDesdeJsonStores(jsonStores, isFree);
        } else {
            // Fallback: Si no hay stores oficiales, generamos enlaces de búsqueda
            return construirTiendasDesdeJsonBasic(jsonBasic, gameTitle, plataformasSet, isFree);
        }
    }

    private static String construirTiendasDesdeJsonStores(String jsonStores, boolean isFree) {
        StringBuilder sb = new StringBuilder("[\n");
        // El JSON de /stores es un array de objetos. Ej: [{"id":1,"game_id":1,"store_id":1,"url":"http..."}, ...]
        Pattern p = Pattern.compile("\\{\"id\":(\\d+),.*?\"store_id\":(\\d+),\"url\":\"([^\"]+)\"\\}");
        Matcher m = p.matcher(jsonStores);
        
        boolean primero = true;
        while (m.find()) {
            if (!primero) {
                sb.append(",\n");
            }
            String storeId = m.group(2);
            String url = m.group(3);
            String storeName = getStoreNameFromUrl(url); // Helper para obtener un nombre legible

            sb.append("      {\n");
            sb.append("        \"tienda\": \"").append(limpiarTexto(storeName)).append("\",\n");
            sb.append("        \"id_externo\": \"").append(storeId).append("\",\n");
            sb.append("        \"url\": \"").append(url).append("\",\n"); 
            sb.append("        \"is_free\": ").append(isFree).append("\n"); 
            sb.append("      }");
            primero = false;
        }
        sb.append("\n    ]");
        return sb.toString();
    }

    private static String construirTiendasDesdeJsonBasic(String json, String gameTitle, Set<String> plataformasSet, boolean isFree) {
        StringBuilder sb = new StringBuilder("[\n");
        String searchKey = "\"stores\":[";
        int startIdx = json.indexOf(searchKey);
        
        if (startIdx != -1) {
            int endIdx = json.indexOf("]", startIdx);
            if (endIdx != -1) {
                String arrayContent = json.substring(startIdx + searchKey.length(), endIdx);
                Pattern p = Pattern.compile("\"store\":\\{\"id\":(\\d+),\"name\":\"([^\"]+)\",\"slug\":\"([^\"]+)\"");
                Matcher m = p.matcher(arrayContent);
                
                boolean primero = true;
                while (m.find()) {
                    if (!primero) sb.append(",\n");
                    
                    String storeId = m.group(1);
                    String storeName = m.group(2);
                    String storeSlug = m.group(3);
                    String url = generarUrlBusqueda(storeSlug, gameTitle);
                    
                    if (storeSlug.equals("steam") || storeSlug.equals("epic-games") || storeSlug.equals("gog") || storeSlug.equals("itch")) {
                        plataformasSet.add("PC");
                    }
                    
                    sb.append("      {\n");
                    sb.append("        \"tienda\": \"").append(limpiarTexto(storeName)).append("\",\n");
                    sb.append("        \"id_externo\": \"").append(storeId).append("\",\n");
                    sb.append("        \"url\": \"").append(url).append("\",\n"); 
                    sb.append("        \"is_free\": ").append(isFree).append("\n"); 
                    sb.append("      }");
                    primero = false;
                }
            }
        }
        sb.append("\n    ]");
        return sb.toString();
    }
    
    private static String generarUrlBusqueda(String storeSlug, String gameTitle) {
        if (gameTitle == null) return "";
        try {
            String query = URLEncoder.encode(gameTitle, StandardCharsets.UTF_8.toString());
            switch (storeSlug.toLowerCase()) {
                case "playstation-store": return "https://store.playstation.com/search/" + query;
                case "xbox-store": return "https://www.xbox.com/search?q=" + query;
                case "nintendo": return "https://www.nintendo.com/search/?q=" + query;
                case "steam": return "https://store.steampowered.com/search/?term=" + query;
                case "epic-games": return "https://store.epicgames.com/browse?q=" + query;
                case "gog": return "https://www.gog.com/en/games?query=" + query;
                default: return ""; 
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static String getStoreNameFromUrl(String url) {
        if (url.contains("store.steampowered.com")) return "Steam";
        if (url.contains("store.playstation.com")) return "PlayStation Store";
        if (url.contains("epicgames.com")) return "Epic Games";
        if (url.contains("gog.com")) return "GOG";
        if (url.contains("xbox.com")) return "Xbox Store";
        if (url.contains("nintendo.com")) return "Nintendo eShop";
        if (url.contains("apple.com")) return "App Store";
        if (url.contains("play.google.com")) return "Google Play";
        if (url.contains("itch.io")) return "itch.io";
        return "Otro";
    }

    private static String extraerValorJsonManual(String json, String key) {
        String search = "\"" + key + "\":";
        int keyIndex = json.indexOf(search);
        if (keyIndex == -1) return null;

        int valueStart = keyIndex + search.length();
        char firstChar = json.charAt(valueStart);

        if (firstChar == '\"') { 
            int valueEnd = json.indexOf('\"', valueStart + 1);
            while (valueEnd != -1 && json.charAt(valueEnd - 1) == '\\') { 
                valueEnd = json.indexOf('\"', valueEnd + 1);
            }
            if (valueEnd != -1) {
                return json.substring(valueStart + 1, valueEnd);
            }
        } else { 
            int valueEnd = -1;
            for (int i = valueStart; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == ',' || c == '}' || c == ']') {
                    valueEnd = i;
                    break;
                }
            }
            if (valueEnd != -1) {
                String val = json.substring(valueStart, valueEnd).trim();
                return val.equals("null") ? null : val;
            }
        }
        return null;
    }

    private static String limpiarTexto(String t) {
        if (t == null) return "";
        return t.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
    
    private static String acortarDescripcion(String desc) {
        if (desc == null) return "";
        if (desc.length() > 300) {
            return desc.substring(0, 297) + "...";
        }
        return desc;
    }
    
    private static String listaAJson(List<String> lista) {
        if (lista == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int k = 0; k < lista.size(); k++) {
            sb.append("\"").append(limpiarTexto(lista.get(k))).append("\"");
            if (k < lista.size() - 1) sb.append(", ");
        }
        sb.append("]");
        return sb.toString();
    }
}