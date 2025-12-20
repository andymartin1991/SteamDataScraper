import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

public class SteamScraper {

    private static final String DB_FILE = "steam_raw.sqlite";
    private static final String OUTPUT_FILE = "steam_games.json.gz";

    public static void main(String[] args) {
        try {
            System.out.println("🚀 Iniciando Exportador Steam (SQLite -> JSON Universal)...");

            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                System.err.println("❌ ERROR: No se encontró el driver JDBC de SQLite.");
                return;
            }

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
                        
                        String jsonProcesado = procesarJuego(appId, jsonCrudo);
                        
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

    private static String procesarJuego(int appId, String json) {
        try {
            if (!json.contains("\"type\":\"game\"")) return null;
            if (json.contains("\"coming_soon\":true")) return null; 
            
            String fecha = extraerFechaISO(json); 
            if (fecha == null) return null; 

            String titulo = extraerValorJsonManual(json, "name");
            String slug = generarSlug(titulo);
            String descCorta = extraerDescripcionCorta(json);
            String imgPrincipal = extraerValorJsonManual(json, "header_image");
            if (imgPrincipal != null) {
                imgPrincipal = imgPrincipal.replace("\\/", "/");
            }
            String storage = extraerTamano(json);
            
            List<String> generos = extraerGeneros(json);
            List<String> galeria = extraerGaleria(json);
            
            Map<String, List<String>> idiomas = procesarIdiomas(json);
            int metacritic = extraerMetacritic(json);
            boolean isFree = json.contains("\"is_free\":true");

            StringBuilder sb = new StringBuilder();
            sb.append("  {\n");
            sb.append("    \"slug\": \"").append(slug).append("\",\n");
            sb.append("    \"titulo\": \"").append(limpiarTexto(titulo)).append("\",\n");
            sb.append("    \"descripcion_corta\": \"").append(limpiarTexto(descCorta)).append("\",\n");
            sb.append("    \"fecha_lanzamiento\": \"").append(fecha).append("\",\n");
            sb.append("    \"storage\": \"").append(storage).append("\",\n");
            
            sb.append("    \"generos\": ").append(listaAJson(generos)).append(",\n");
            sb.append("    \"plataformas\": [\"PC\"],\n"); 
            sb.append("    \"img_principal\": \"").append(imgPrincipal).append("\",\n");
            sb.append("    \"galeria\": ").append(listaAJson(galeria)).append(",\n");
            
            sb.append("    \"idiomas\": {\n");
            sb.append("      \"voces\": ").append(listaAJson(idiomas.get("voces"))).append(",\n");
            sb.append("      \"textos\": ").append(listaAJson(idiomas.get("textos"))).append("\n");
            sb.append("    },\n");
            
            sb.append("    \"metacritic\": ").append(metacritic).append(",\n");
            
            sb.append("    \"tiendas\": [\n");
            sb.append("      {\n");
            sb.append("        \"tienda\": \"Steam\",\n");
            // ELIMINADO: "plataforma": "PC"
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

    // --- UTILIDADES ---

    private static String generarSlug(String titulo) {
        if (titulo == null) return "unknown";
        String normalized = Normalizer.normalize(titulo, Normalizer.Form.NFD);
        String slug = normalized.replaceAll("[\\p{InCombiningDiacriticalMarks}]", "");
        slug = slug.toLowerCase();
        slug = slug.replaceAll("[^a-z0-9\\s-]", ""); 
        slug = slug.replaceAll("\\s+", "-"); 
        slug = slug.replaceAll("-+", "-"); 
        return slug.trim();
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
        return (t == null) ? "" : t.replace("\\", "\\\\").replace("\"", "\\\"");
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