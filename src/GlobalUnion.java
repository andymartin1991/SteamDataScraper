import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class GlobalUnion {

    private static final String STEAM_FILE = "steam_games.json.gz";
    private static final String RAWG_FILE = "rawg_games.json.gz";
    private static final String OUTPUT_FILE = "global_games.json.gz";

    public static void main(String[] args) {
        ObjectMapper mapper = new ObjectMapper();
        
        try {
            System.out.println("🚀 Iniciando Fusión Global (Steam + RAWG) con Validación de Año, Tipo y Limpieza de Galería...");

            // --- 1. Cargar todos los juegos de Steam en un mapa por slug ---
            System.out.println("   -> Cargando juegos de Steam en memoria...");
            Map<String, JsonNode> steamGames = loadGamesToMap(STEAM_FILE, mapper);
            int totalSteamInicial = steamGames.size();
            System.out.println("   -> " + totalSteamInicial + " juegos de Steam cargados.");

            // Contadores para estadísticas
            int totalRawg = 0;
            int mergedCount = 0;
            int rawgOnlyCount = 0;
            int conflictosResueltos = 0;

            // --- 2. Iterar sobre RAWG y fusionar con Steam ---
            System.out.println("   -> Procesando y fusionando juegos de RAWG...");
            
            try (Writer writer = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(OUTPUT_FILE)), "UTF-8")) {
                writer.write("[\n");

                JsonFactory factory = mapper.getFactory();
                try (InputStream is = new GZIPInputStream(new FileInputStream(RAWG_FILE));
                     JsonParser parser = factory.createParser(is)) {

                    if (parser.nextToken() != JsonToken.START_ARRAY) {
                        throw new IllegalStateException("Se esperaba un array JSON en " + RAWG_FILE);
                    }

                    boolean primero = true;
                    while (parser.nextToken() == JsonToken.START_OBJECT) {
                        JsonNode rawgGame = mapper.readTree(parser);
                        totalRawg++;
                        
                        String slug = rawgGame.path("slug").asText();
                        JsonNode finalGame;

                        // Si el juego de RAWG existe en Steam por slug...
                        if (steamGames.containsKey(slug)) {
                            JsonNode steamGame = steamGames.get(slug);
                            
                            // VALIDACIÓN CRÍTICA: ¿Son realmente el mismo juego? (Chequeo de Año y Tipo)
                            if (sonElMismoJuego(steamGame, rawgGame)) {
                                finalGame = fusionarJuegos(steamGame, rawgGame, mapper);
                                steamGames.remove(slug); // Lo quitamos del mapa para no duplicarlo al final
                                mergedCount++;
                            } else {
                                // Conflicto: Mismo slug pero distinto año o distinto tipo (Game vs DLC).
                                // Tratamos el de RAWG como independiente.
                                ObjectNode rawgGameModificado = (ObjectNode) rawgGame.deepCopy();
                                
                                // Generamos un slug único para evitar colisión
                                String sufijo = extraerAnio(rawgGame) > 0 ? String.valueOf(extraerAnio(rawgGame)) : "rawg";
                                String nuevoSlug = slug + "-" + sufijo;
                                
                                // Si aún así colisionara (muy raro), añadimos random
                                if (nuevoSlug.equals(slug)) nuevoSlug = slug + "-v2";
                                
                                rawgGameModificado.put("slug", nuevoSlug);
                                
                                // Limpieza también para juegos exclusivos de RAWG
                                limpiarGaleria(rawgGameModificado);
                                
                                finalGame = rawgGameModificado;
                                rawgOnlyCount++;
                                conflictosResueltos++;
                            }
                        } else {
                            // Si no existe en Steam, es un exclusivo de consola/móvil
                            ObjectNode rawgGameNode = (ObjectNode) rawgGame;
                            limpiarGaleria(rawgGameNode); // Limpieza
                            finalGame = rawgGameNode;
                            rawgOnlyCount++;
                        }

                        if (!primero) {
                            writer.write(",\n");
                        }
                        writer.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(finalGame));
                        primero = false;
                    }
                }

                // --- 3. Añadir los juegos restantes de Steam (exclusivos de PC) ---
                int steamOnlyCount = steamGames.size();
                System.out.println("   -> Añadiendo " + steamOnlyCount + " juegos exclusivos de Steam...");
                
                for (JsonNode steamGame : steamGames.values()) {
                    // Limpieza también para exclusivos de Steam
                    limpiarGaleria((ObjectNode) steamGame);
                    
                    writer.write(",\n");
                    writer.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(steamGame));
                }

                writer.write("\n]");
                
                // --- 4. Imprimir Estadísticas Finales ---
                System.out.println("\n📊 ESTADÍSTICAS DE FUSIÓN:");
                System.out.println("   =========================================");
                System.out.println("   📥 ORIGEN:");
                System.out.println("      - Total Steam: " + totalSteamInicial);
                System.out.println("      - Total RAWG:  " + totalRawg);
                System.out.println("   -----------------------------------------");
                System.out.println("   🔄 PROCESO:");
                System.out.println("      - 🔗 Fusionados (Coinciden): " + mergedCount);
                System.out.println("      - 🛡️ Conflictos Resueltos:   " + conflictosResueltos + " (Año o Tipo distinto)");
                System.out.println("      - 🎮 Solo en RAWG (Nuevos):  " + rawgOnlyCount);
                System.out.println("      - 💻 Solo en Steam (PC):     " + steamOnlyCount);
                System.out.println("   -----------------------------------------");
                System.out.println("   📤 RESULTADO FINAL:");
                System.out.println("      - Total Global: " + (mergedCount + rawgOnlyCount + steamOnlyCount));
                System.out.println("   =========================================");
            }

            System.out.println("\n✅ Fusión completada. Archivo: " + OUTPUT_FILE);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Map<String, JsonNode> loadGamesToMap(String filePath, ObjectMapper mapper) throws Exception {
        Map<String, JsonNode> gameMap = new HashMap<>();
        JsonFactory factory = mapper.getFactory();

        try (InputStream is = new GZIPInputStream(new FileInputStream(filePath));
             JsonParser parser = factory.createParser(is)) {

            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IllegalStateException("Se esperaba un array JSON en " + filePath);
            }

            while (parser.nextToken() == JsonToken.START_OBJECT) {
                JsonNode gameNode = mapper.readTree(parser);
                String slug = gameNode.path("slug").asText();
                if (slug != null && !slug.isEmpty()) {
                    gameMap.put(slug, gameNode);
                }
            }
        }
        return gameMap;
    }
    
    // --- LÓGICA DE VALIDACIÓN DE FUSIÓN ---
    
    private static boolean sonElMismoJuego(JsonNode steam, JsonNode rawg) {
        // 1. Validación de Tipo (Game vs DLC)
        String tipoSteam = steam.path("tipo").asText("game");
        String tipoRawg = rawg.path("tipo").asText("game");
        
        // Si son tipos distintos (uno game y otro dlc), NO son el mismo
        if (!tipoSteam.equalsIgnoreCase(tipoRawg)) {
            return false;
        }

        // 2. Validación de Año
        int anioSteam = extraerAnio(steam);
        int anioRawg = extraerAnio(rawg);
        
        // Si alguno no tiene año, asumimos que SÍ son el mismo (ante la duda, fusionamos por slug)
        if (anioSteam == 0 || anioRawg == 0) return true;
        
        // Si la diferencia es mayor a 1 año, NO son el mismo juego
        return Math.abs(anioSteam - anioRawg) <= 1;
    }
    
    private static int extraerAnio(JsonNode game) {
        String fecha = game.path("fecha_lanzamiento").asText();
        if (fecha == null || fecha.isEmpty()) return 0;
        try {
            // Formato esperado: YYYY-MM-DD
            if (fecha.length() >= 4) {
                return Integer.parseInt(fecha.substring(0, 4));
            }
        } catch (Exception e) {
            // Ignorar errores de parseo
        }
        return 0;
    }

    private static JsonNode fusionarJuegos(JsonNode steamGame, JsonNode rawgGame, ObjectMapper mapper) {
        // Clonamos el juego de Steam para no modificar el original
        ObjectNode base = (ObjectNode) steamGame.deepCopy();

        // 1. Metacritic: prevalece el más alto
        int steamMetacritic = base.path("metacritic").asInt(0);
        int rawgMetacritic = rawgGame.path("metacritic").asInt(0);
        if (rawgMetacritic > steamMetacritic) {
            base.put("metacritic", rawgMetacritic);
        }

        // 2. Plataformas: añadir las de RAWG si no están en Steam
        fusionarArray(base, rawgGame, "plataformas");

        // 3. Géneros: añadir los de RAWG si no están en Steam
        fusionarArray(base, rawgGame, "generos");

        // 4. Galerías: añadir las de RAWG si no están en Steam
        fusionarArray(base, rawgGame, "galeria");

        // 5. Tiendas: lógica especial (filtrar duplicados de PC)
        ArrayNode steamStores = (ArrayNode) base.path("tiendas");
        ArrayNode rawgStores = (ArrayNode) rawgGame.path("tiendas");
        Set<String> existingStoreNames = new HashSet<>();
        if (steamStores != null) {
            steamStores.forEach(node -> existingStoreNames.add(node.path("tienda").asText().toLowerCase()));
        } else {
            steamStores = base.putArray("tiendas");
        }

        if (rawgStores != null) {
            for (JsonNode storeNode : rawgStores) {
                String storeName = storeNode.path("tienda").asText().toLowerCase();
                // Añadimos si la tienda no es de PC (para evitar duplicados como "Steam") y no existe ya
                if (!storeName.equals("steam") && !storeName.equals("gog") && !storeName.equals("epic games") && !existingStoreNames.contains(storeName)) {
                    steamStores.add(storeNode);
                }
            }
        }
        
        // 6. LIMPIEZA FINAL: Eliminar imagen principal de la galería
        limpiarGaleria(base);
        
        return base;
    }

    // Método auxiliar para fusionar arrays de strings simples (plataformas, generos, galeria)
    private static void fusionarArray(ObjectNode base, JsonNode source, String fieldName) {
        ArrayNode baseArray = (ArrayNode) base.path(fieldName);
        ArrayNode sourceArray = (ArrayNode) source.path(fieldName);
        
        Set<String> existingItems = new HashSet<>();
        if (baseArray != null) {
            baseArray.forEach(node -> existingItems.add(node.asText().toLowerCase()));
        } else {
            baseArray = base.putArray(fieldName);
        }

        if (sourceArray != null) {
            for (JsonNode node : sourceArray) {
                String item = node.asText();
                if (!existingItems.contains(item.toLowerCase())) {
                    baseArray.add(item);
                    existingItems.add(item.toLowerCase());
                }
            }
        }
    }
    
    // NUEVO MÉTODO: Elimina la imagen principal de la galería para evitar duplicados
    private static void limpiarGaleria(ObjectNode game) {
        String imgPrincipal = game.path("img_principal").asText("");
        if (imgPrincipal.isEmpty()) return;
        
        ArrayNode galeria = (ArrayNode) game.path("galeria");
        if (galeria == null) return;
        
        Iterator<JsonNode> it = galeria.iterator();
        while (it.hasNext()) {
            JsonNode imgNode = it.next();
            if (imgNode.asText().equals(imgPrincipal)) {
                it.remove();
            }
        }
    }
}