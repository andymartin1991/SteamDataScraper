import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

public class SteamUpcomingScraper {

    private static final String DB_FILE = "steam_raw.sqlite";
    private static final String OUTPUT_FILE = "steam_proximos_games.json.gz";

    public static void main(String[] args) {
        try {
            System.out.println("🚀 Iniciando SteamUpcomingScraper (Próximos Lanzamientos de Steam)...");

            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                System.err.println("❌ ERROR: No se encontró el driver JDBC de SQLite.");
                return;
            }
            
            // PASO 1: DETECTAR DUPLICADOS (Slugs y Títulos)
            System.out.println("🔍 Analizando duplicados para aplicar sufijos inteligentes...");
            Set<Integer> idsConflictivos = detectarIdsConflictivos();
            System.out.println("⚠️ Se detectaron " + idsConflictivos.size() + " juegos con nombres/slugs duplicados que serán renombrados.");

            // PASO 2: EXPORTACIÓN
            try (Writer w = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(OUTPUT_FILE)), "UTF-8")) {
                w.write("[\n");
                
                try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
                     Statement stmt = conn.createStatement()) {
                    
                    stmt.setFetchSize(1000); 
                    ResultSet rs = stmt.executeQuery("SELECT app_id, json_data FROM steam_raw_data");
                    
                    int procesados = 0;
                    int exportados = 0;
                    boolean primero = true;

                    while (rs.next()) {
                        int appId = rs.getInt("app_id");
                        String jsonCrudo = rs.getString("json_data");
                        
                        // Pasamos el set de conflictivos para decidir si renombrar
                        String jsonProcesado = procesarJuego(appId, jsonCrudo, idsConflictivos.contains(appId));
                        
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
    
    // --- PASO 1: DETECCIÓN DE CONFLICTOS ---
    private static Set<Integer> detectarIdsConflictivos() {
        Set<Integer> conflictivos = new HashSet<>();
        Map<String, List<Integer>> slugMap = new HashMap<>();
        
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE);
             Statement stmt = conn.createStatement()) {
            
            ResultSet rs = stmt.executeQuery("SELECT app_id, json_data FROM steam_raw_data");
            while (rs.next()) {
                int appId = rs.getInt("app_id");
                String json = rs.getString("json_data");
                
                // Filtro INVERSO: Solo procesar si es Coming Soon
                if (!esComingSoon(json)) continue;
                
                String tipo = extraerValorJsonManual(json, "type");
                if (tipo == null || (!tipo.equals("game") && !tipo.equals("dlc"))) continue;

                String titulo = extraerValorJsonManual(json, "name");
                String slug = generarSlug(titulo);
                
                slugMap.computeIfAbsent(slug, k -> new ArrayList<>()).add(appId);
            }
            
            for (List<Integer> ids : slugMap.values()) {
                if (ids.size() > 1) {
                    conflictivos.addAll(ids);
                }
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        return conflictivos;
    }

    private static String procesarJuego(int appId, String json, boolean esConflictivo) {
        try {
            // FILTRO PRINCIPAL: Solo juegos que AÚN NO han salido
            if (!esComingSoon(json)) return null;

            String tipo = extraerValorJsonManual(json, "type");
            if (tipo == null) return null;
            if (!tipo.equals("game") && !tipo.equals("dlc")) return null; 
            
            String fecha = extraerFechaISO(json); 
            // Nota: En coming soon, la fecha puede ser null o "TBA", lo cual es aceptable aquí.
            if (fecha == null) fecha = "TBA";

            String titulo = extraerValorJsonManual(json, "name");
            String slug = generarSlug(titulo);
            
            if (esConflictivo) {
                slug = slug + "-steam-" + appId;
                titulo = titulo + " (Coming Soon)";
            }

            String descCorta = extraerDescripcionCorta(json);
            String imgPrincipal = extraerValorJsonManual(json, "header_image");
            if (imgPrincipal != null) {
                imgPrincipal = imgPrincipal.replace("\\/", "/");
            }
            String storage = extraerTamano(json);
            
            List<String> generos = extraerGeneros(json);
            List<String> galeria = extraerGaleria(json);
            List<Map<String, String>> videos = extraerVideos(json);
            
            List<String> developers = extraerListaSimple(json, "developers");
            List<String> publishers = extraerListaSimple(json, "publishers");
            
            Map<String, List<String>> idiomas = procesarIdiomas(json);
            int metacritic = extraerMetacritic(json);
            boolean isFree = json.contains("\"is_free\":true");
            
            // NUEVO: Extracción de Edad Recomendada (Prioridad PEGI)
            int requiredAge = extraerRequiredAge(json);

            StringBuilder sb = new StringBuilder();
            sb.append("  {\n");
            sb.append("    \"slug\": \"").append(slug).append("\",\n");
            sb.append("    \"titulo\": \"").append(limpiarTexto(titulo)).append("\",\n");
            sb.append("    \"tipo\": \"").append(tipo).append("\",\n");
            sb.append("    \"descripcion_corta\": \"").append(limpiarTexto(descCorta)).append("\",\n");
            sb.append("    \"fecha_lanzamiento\": \"").append(fecha).append("\",\n");
            sb.append("    \"storage\": \"").append(storage).append("\",\n");
            
            sb.append("    \"generos\": ").append(listaAJson(generos)).append(",\n");
            sb.append("    \"plataformas\": [\"PC\"],\n"); 
            sb.append("    \"img_principal\": \"").append(imgPrincipal).append("\",\n");
            sb.append("    \"galeria\": ").append(listaAJson(galeria)).append(",\n");
            sb.append("    \"videos\": ").append(listaMapAJson(videos)).append(",\n");
            
            sb.append("    \"desarrolladores\": ").append(listaAJson(developers)).append(",\n");
            sb.append("    \"editores\": ").append(listaAJson(publishers)).append(",\n");
            
            sb.append("    \"idiomas\": {\n");
            sb.append("      \"voces\": ").append(listaAJson(idiomas.get("voces"))).append(",\n");
            sb.append("      \"textos\": ").append(listaAJson(idiomas.get("textos"))).append("\n");
            sb.append("    },\n");
            
            sb.append("    \"metacritic\": ").append(metacritic).append(",\n");
            sb.append("    \"edad_recomendada\": ").append(requiredAge).append(",\n");
            
            sb.append("    \"tiendas\": [\n");
            sb.append("      {\n");
            sb.append("        \"tienda\": \"Steam\",\n");
            sb.append("        \"id_externo\": \"").append(appId).append("\",\n");
            sb.append("        \"url\": \"https://store.steampowered.com/app/").append(appId).append("\",\n");
            sb.append("        \"is_free\": ").append(isFree).append("\n");
            sb.append("      }\n");
            sb.append("    ]\n");
            sb.append("  }");
            
            return sb.toString();

        } catch (Exception e) {
            return null;
        }
    }
    
    // --- LÓGICA DE FILTRADO COMING SOON ---
    private static boolean esComingSoon(String json) {
        // 1. Check explícito de flag
        if (json.contains("\"coming_soon\":true")) return true;
        
        // 2. Check de fecha futura (por si acaso el flag falla pero la fecha es futura)
        String fecha = extraerFechaISO(json);
        if (fecha != null) {
            try {
                LocalDate date = LocalDate.parse(fecha);
                if (date.isAfter(LocalDate.now())) {
                    return true;
                }
            } catch (Exception e) {}
        }
        
        return false;
    }

    // --- UTILIDADES (Idénticas a SteamScraper) ---

    private static String generarSlug(String titulo) {
        if (titulo == null) return "unknown";
        String normalized = Normalizer.normalize(titulo, Normalizer.Form.NFD);
        String slug = normalized.replaceAll("[\\p{InCombiningDiacriticalMarks}]", "");
        slug = slug.toLowerCase();
        slug = slug.replaceAll("[^\\p{L}\\p{N}\\s-]", ""); 
        slug = slug.replaceAll("\\s+", "-"); 
        slug = slug.replaceAll("-+", "-"); 
        if (slug.startsWith("-")) slug = slug.substring(1);
        if (slug.endsWith("-")) slug = slug.substring(0, slug.length() - 1);
        if (slug.isEmpty()) return "unknown";
        return slug;
    }

    private static String extraerFechaISO(String json) {
        String rawDate = extraerValorJsonManual(json, "date");
        if (rawDate == null || rawDate.contains("TBA") || rawDate.trim().isEmpty()) return null;
        try {
            String[] parts = rawDate.replace(",", "").split(" ");
            if (parts.length < 3) return null; 
            String mesStr = parts[0].substring(0, 3).toLowerCase();
            String dia = parts[1];
            String anio = parts[2];
            if (dia.length() == 1) dia = "0" + dia;
            String mes = switch (mesStr) {
                case "jan" -> "01"; case "feb" -> "02"; case "mar" -> "03";
                case "apr" -> "04"; case "may" -> "05"; case "jun" -> "06";
                case "jul" -> "07"; case "aug" -> "08"; case "sep" -> "09";
                case "oct" -> "10"; case "nov" -> "11"; case "dec" -> "12";
                default -> "01";
            };
            return anio + "-" + mes + "-" + dia;
        } catch (Exception e) {
            return null; 
        }
    }

    private static String extraerDescripcionCorta(String json) {
        String desc = extraerValorJsonManual(json, "short_description");
        if (desc == null) return "";
        desc = desc.replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        if (desc.length() > 300) {
            return desc.substring(0, 297) + "...";
        }
        return desc;
    }

    private static List<String> extraerGaleria(String json) {
        List<String> screenshots = new ArrayList<>();
        int idx = json.indexOf("\"screenshots\"");
        if (idx == -1) return screenshots;
        
        int count = 0;
        int startSearch = idx;
        while (count < 3) {
            int pathFullIdx = json.indexOf("\"path_full\":", startSearch);
            if (pathFullIdx == -1) break;
            
            int startQuote = json.indexOf("\"", pathFullIdx + 12);
            int endQuote = json.indexOf("\"", startQuote + 1);
            
            if (startQuote != -1 && endQuote != -1) {
                String url = json.substring(startQuote + 1, endQuote);
                url = url.replace("\\/", "/");
                screenshots.add(url);
                startSearch = endQuote;
                count++;
            } else {
                break;
            }
        }
        return screenshots;
    }
    
    private static List<Map<String, String>> extraerVideos(String json) {
        List<Map<String, String>> videos = new ArrayList<>();
        int idxMovies = json.indexOf("\"movies\"");
        if (idxMovies == -1) return videos;

        int endMovies = json.indexOf("]", idxMovies);
        if (endMovies == -1) return videos;

        String moviesSection = json.substring(idxMovies, endMovies + 1);
        
        Pattern pId = Pattern.compile("\"id\":\\s*(\\d+)");
        Matcher mId = pId.matcher(moviesSection);
        
        List<Integer> startIndices = new ArrayList<>();
        while (mId.find()) {
            startIndices.add(mId.start());
        }
        
        for (int i = 0; i < startIndices.size(); i++) {
            int start = startIndices.get(i);
            int end = (i < startIndices.size() - 1) ? startIndices.get(i+1) : moviesSection.length();
            String block = moviesSection.substring(start, end);
            
            String name = extraerValorJsonManual(block, "name");
            String thumbnail = extraerValorJsonManual(block, "thumbnail");
            String url = null;
            
            Matcher mMp4Max = Pattern.compile("\"mp4\":\\s*\\{.*?\"max\":\"([^\"]+)\"").matcher(block);
            if (mMp4Max.find()) url = mMp4Max.group(1);
            
            if (url == null) {
                Matcher mMp4480 = Pattern.compile("\"mp4\":\\s*\\{.*?\"480\":\"([^\"]+)\"").matcher(block);
                if (mMp4480.find()) url = mMp4480.group(1);
            }
            
            if (url == null) {
                 Matcher mWebm = Pattern.compile("\"webm\":\\s*\\{.*?\"max\":\"([^\"]+)\"").matcher(block);
                 if (mWebm.find()) url = mWebm.group(1);
            }
            
            if (url == null) {
                url = extraerValorJsonManual(block, "hls_h264"); 
                if (url == null) url = extraerValorJsonManual(block, "dash_h264");
            }
            
            if (url != null && name != null) {
                Map<String, String> v = new HashMap<>();
                v.put("titulo", limpiarTexto(name));
                v.put("url", url.replace("\\/", "/"));
                if (thumbnail != null) v.put("thumbnail", thumbnail.replace("\\/", "/"));
                videos.add(v);
            }
        }
        return videos;
    }

    private static List<String> extraerGeneros(String json) {
        List<String> generos = new ArrayList<>();
        int idxGenres = json.indexOf("\"genres\"");
        if (idxGenres == -1) return generos;
        
        int endGenres = json.indexOf("]", idxGenres);
        if (endGenres == -1) return generos;
        
        String genresSection = json.substring(idxGenres, endGenres + 1);
        Pattern p = Pattern.compile("\"description\":\"([^\"]+)\"");
        Matcher m = p.matcher(genresSection);
        while(m.find()) {
            generos.add(m.group(1));
        }
        return generos;
    }
    
    private static List<String> extraerListaSimple(String json, String key) {
        List<String> lista = new ArrayList<>();
        int idx = json.indexOf("\"" + key + "\"");
        if (idx == -1) return lista;
        
        int startArray = json.indexOf("[", idx);
        int endArray = json.indexOf("]", startArray);
        if (startArray == -1 || endArray == -1) return lista;
        
        String content = json.substring(startArray + 1, endArray);
        Pattern p = Pattern.compile("\"([^\"]+)\"");
        Matcher m = p.matcher(content);
        while(m.find()) {
            lista.add(m.group(1));
        }
        return lista;
    }
    
    private static int extraerMetacritic(String json) {
        try {
            Pattern p = Pattern.compile("\"metacritic\":\\s*\\{\\s*\"score\":\\s*(\\d+)");
            Matcher m = p.matcher(json);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        } catch (Exception e) {}
        return 0;
    }
    
    private static int extraerRequiredAge(String json) {
        // ESTRATEGIA: Prioridad PEGI > ESRB > Máximo Global
        
        // 1. Buscar PEGI explícito
        int pegi = buscarRatingEspecifico(json, "pegi");
        if (pegi > 0) return pegi;
        
        // 2. Buscar ESRB explícito y normalizar
        int esrb = buscarRatingEspecifico(json, "esrb"); // Devuelve 0, 10, 13, 17, 18
        if (esrb > 0) {
            // Normalizar ESRB a PEGI
            if (esrb >= 17) return 16;
            if (esrb >= 13) return 12;
            if (esrb >= 10) return 7;
            return esrb;
        }
        
        // 3. Fallback: Máximo global (lo que teníamos antes)
        int maxAge = 0;
        try {
            Pattern p = Pattern.compile("\"required_age\":\\s*\"?(\\d+)\"?");
            Matcher m = p.matcher(json);
            while (m.find()) {
                try {
                    int age = Integer.parseInt(m.group(1));
                    if (age > maxAge) maxAge = age;
                } catch (NumberFormatException e) {}
            }
        } catch (Exception e) {}
        
        // Normalización final del fallback
        if (maxAge <= 0) return 0;
        if (maxAge <= 3) return 3;
        if (maxAge <= 7) return 7;
        if (maxAge <= 10) return 7;
        if (maxAge <= 12) return 12;
        if (maxAge <= 14) return 12;
        if (maxAge <= 16) return 16;
        if (maxAge <= 17) return 16;
        return 18;
    }
    
    private static int buscarRatingEspecifico(String json, String sistema) {
        try {
            // Buscamos el bloque del sistema: "pegi": { ... }
            int idxSystem = json.indexOf("\"" + sistema + "\":");
            if (idxSystem == -1) return 0;
            
            // Buscamos el cierre del objeto para limitar la búsqueda
            int idxEnd = json.indexOf("}", idxSystem);
            if (idxEnd == -1) return 0;
            
            String block = json.substring(idxSystem, idxEnd);
            
            // Buscamos "rating": "X" dentro del bloque
            Pattern p = Pattern.compile("\"rating\":\\s*\"?([a-zA-Z0-9]+)\"?");
            Matcher m = p.matcher(block);
            if (m.find()) {
                String val = m.group(1).toLowerCase();
                
                // Parsear valores numéricos directos (PEGI suele ser "3", "7", etc.)
                if (val.matches("\\d+")) {
                    return Integer.parseInt(val);
                }
                
                // Parsear códigos de ESRB (e, e10, t, m, ao)
                if (sistema.equals("esrb")) {
                    if (val.equals("e")) return 0;
                    if (val.equals("e10")) return 10;
                    if (val.equals("t")) return 13;
                    if (val.equals("m")) return 17;
                    if (val.equals("ao")) return 18;
                }
            }
        } catch (Exception e) {}
        return 0;
    }
    
    private static String extraerTamano(String json) {
        try {
            Pattern pSection = Pattern.compile("(Storage|Hard Drive):.*?(\\d+\\.?\\d*)\\s*(GB|MB)");
            Matcher m = pSection.matcher(json);
            if (m.find()) return m.group(2) + " " + m.group(3);
        } catch (Exception e) {}
        return "N/A";
    }

    private static Map<String, List<String>> procesarIdiomas(String json) {
        Map<String, List<String>> result = new HashMap<>();
        List<String> voces = new ArrayList<>();
        List<String> textos = new ArrayList<>();
        result.put("voces", voces);
        result.put("textos", textos);

        String rawLangs = extraerValorJsonManual(json, "supported_languages");
        if (rawLangs == null || rawLangs.isEmpty()) return result;

        String cleanLangs = rawLangs.replaceAll("languages with full audio support", "");
        cleanLangs = cleanLangs.replace("with full audio support", "");

        cleanLangs = cleanLangs.replace("\\r", ",");
        cleanLangs = cleanLangs.replace("\\n", ",");
        cleanLangs = cleanLangs.replace("\r", ",");
        cleanLangs = cleanLangs.replace("\n", ",");
        cleanLangs = cleanLangs.replaceAll("<br\\s*/?>", ",");

        String[] langParts = cleanLangs.split(",");

        for (String part : langParts) {
            boolean hasVoice = part.contains("*");

            String langName = part
                    .replace("*", "")              
                    .replaceAll("\\[.*?\\]", "")   
                    .replaceAll("<[^>]*>", "")     
                    .trim();                       

            if (!langName.isEmpty()) {
                if (!textos.contains(langName)) {
                    textos.add(langName);
                }
                if (hasVoice && !voces.contains(langName)) {
                    voces.add(langName);
                }
            }
        }
        return result;
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
        if (t == null) return "";
        return t.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
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
    
    private static String listaMapAJson(List<Map<String, String>> lista) {
        if (lista == null || lista.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < lista.size(); i++) {
            Map<String, String> map = lista.get(i);
            sb.append("      {\n");
            sb.append("        \"titulo\": \"").append(map.get("titulo")).append("\",\n");
            if (map.containsKey("thumbnail")) {
                sb.append("        \"thumbnail\": \"").append(map.get("thumbnail")).append("\",\n");
            }
            sb.append("        \"url\": \"").append(map.get("url")).append("\"\n");
            sb.append("      }");
            if (i < lista.size() - 1) sb.append(",\n");
        }
        sb.append("\n    ]");
        return sb.toString();
    }
}