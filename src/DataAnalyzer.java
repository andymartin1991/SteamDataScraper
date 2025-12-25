import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.zip.GZIPInputStream;

public class DataAnalyzer {

    private static final String REPORT_FILE = "analysis_report.txt";

    public static void main(String[] args) {
        try {
            System.out.println("🔍 Iniciando Analizador de Datos JSON.GZ (Modo Estricto)...");

            // 1. Escanear archivos .json.gz en la raíz
            File rootDir = new File(".");
            File[] files = rootDir.listFiles((dir, name) -> name.endsWith(".json.gz"));

            if (files == null || files.length == 0) {
                System.out.println("❌ No se encontraron archivos .json.gz en la raíz.");
                return;
            }

            System.out.println("\n📂 Archivos encontrados:");
            for (int i = 0; i < files.length; i++) {
                System.out.println("   [" + (i + 1) + "] " + files[i].getName());
            }

            // 2. Pedir selección al usuario
            Scanner scanner = new Scanner(System.in);
            System.out.print("\n👉 Selecciona el número del archivo a analizar: ");
            int selection = -1;
            if (scanner.hasNextInt()) {
                selection = scanner.nextInt();
            }

            if (selection < 1 || selection > files.length) {
                System.out.println("❌ Selección inválida.");
                return;
            }

            File selectedFile = files[selection - 1];
            System.out.println("\n🚀 Analizando: " + selectedFile.getName() + " ... (Esto puede tardar un poco)");

            analizarArchivo(selectedFile);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void analizarArchivo(File file) {
        ObjectMapper mapper = new ObjectMapper();
        JsonFactory factory = mapper.getFactory();

        Map<String, Integer> slugCounts = new HashMap<>();
        Map<String, Integer> titleCounts = new HashMap<>();
        
        // Para guardar ejemplos de colisiones
        Map<String, List<String>> titleExamples = new HashMap<>();
        Map<String, List<String>> slugExamples = new HashMap<>(); 

        int totalGames = 0;

        try (InputStream is = new GZIPInputStream(new FileInputStream(file));
             JsonParser parser = factory.createParser(is)) {

            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IllegalStateException("Se esperaba un array JSON.");
            }

            while (parser.nextToken() == JsonToken.START_OBJECT) {
                JsonNode game = mapper.readTree(parser);
                totalGames++;

                String slug = game.path("slug").asText();
                String title = game.path("titulo").asText();
                String year = game.path("fecha_lanzamiento").asText("????-??-??");
                String storage = game.path("storage").asText("N/A");
                
                String info = String.format("Título: %s | Fecha: %s | Tamaño: %s", title, year, storage);
                
                // SIN NORMALIZACIÓN: Usamos el título tal cual viene en el JSON
                String titleKey = title; 

                // Contar Slugs
                slugCounts.put(slug, slugCounts.getOrDefault(slug, 0) + 1);
                
                if (!slugExamples.containsKey(slug)) {
                    slugExamples.put(slug, new ArrayList<>());
                }
                slugExamples.get(slug).add(info);

                // Contar Títulos
                if (titleKey != null && !titleKey.isEmpty()) {
                    titleCounts.put(titleKey, titleCounts.getOrDefault(titleKey, 0) + 1);
                    
                    if (!titleExamples.containsKey(titleKey)) {
                        titleExamples.put(titleKey, new ArrayList<>());
                    }
                    titleExamples.get(titleKey).add(info);
                }
                
                if (totalGames % 10000 == 0) {
                    System.out.print(".");
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Error leyendo el archivo: " + e.getMessage());
            return;
        }

        // --- GENERAR REPORTE EN ARCHIVO ---
        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(REPORT_FILE)))) {
            writer.println("📊 REPORTE DE ANÁLISIS DETALLADO");
            writer.println("=========================================");
            writer.println("Archivo Analizado: " + file.getName());
            writer.println("Total Juegos: " + totalGames);
            writer.println("=========================================\n");

            // Reporte de Slugs
            long duplicateSlugs = slugCounts.values().stream().filter(c -> c > 1).count();
            writer.println("🔴 SLUGS DUPLICADOS: " + duplicateSlugs);
            writer.println("-----------------------------------------");
            
            slugCounts.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .forEach(e -> {
                    writer.println("Slug: '" + e.getKey() + "' (" + e.getValue() + " veces)");
                    List<String> examples = slugExamples.get(e.getKey());
                    for (String ex : examples) {
                        writer.println("   -> " + ex);
                    }
                    writer.println();
                });

            writer.println("\n=========================================\n");

            // Reporte de Títulos
            long duplicateTitles = titleCounts.values().stream().filter(c -> c > 1).count();
            writer.println("🟠 TÍTULOS DUPLICADOS (EXACTOS): " + duplicateTitles);
            writer.println("-----------------------------------------");
            
            titleCounts.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .forEach(e -> {
                    writer.println("Título: '" + e.getKey() + "' (" + e.getValue() + " veces)");
                    List<String> examples = titleExamples.get(e.getKey());
                    for (String ex : examples) {
                        writer.println("   -> " + ex);
                    }
                    writer.println();
                });
                
            System.out.println("\n\n✅ Reporte generado exitosamente en: " + REPORT_FILE);
            
        } catch (Exception e) {
            System.err.println("❌ Error escribiendo el reporte: " + e.getMessage());
        }
    }
}