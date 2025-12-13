import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import java.io.File;

public class JsonToParquet {

    private static final String JSON_INPUT_FILE = "juegos_nuevos.json";
    private static final String PARQUET_OUTPUT_DIRECTORY = "SteamGames.parquet";

    public static void main(String[] args) {

        // --- PARCHE OBLIGATORIO PARA EJECUTAR SPARK EN WINDOWS ---
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            System.setProperty("hadoop.home.dir", System.getProperty("user.dir"));
        }

        SparkSession spark = null;
        try {
            // 1. Crear la SparkSession, el punto de entrada a toda la funcionalidad de Spark
            spark = SparkSession.builder()
                    .appName("JsonToParquetConverter")
                    .master("local[*]") // Usar todos los cores de la máquina local
                    .config("spark.driver.bindAddress", "127.0.0.1") // Necesario en algunos sistemas Windows
                    .config("spark.sql.warehouse.dir", new File("spark-warehouse").getAbsolutePath()) // Evita problemas de permisos en C:\
                    .getOrCreate();

            System.out.println("🚀 Iniciando conversión de JSON a Parquet con Apache Spark...");

            // 2. Leer el JSON en un DataFrame, Spark infiere el esquema automáticamente
            Dataset<Row> df = spark.read().option("multiline", "true").json(JSON_INPUT_FILE);

            System.out.println("✅ JSON leído correctamente. Esquema inferido por Spark:");
            df.printSchema();

            // 3. Seleccionar todas las columnas EXCEPTO 'supported_languages_raw'
            Dataset<Row> dfFinal = df.drop("supported_languages_raw");

            System.out.println("✨ Mostrando 20 primeros registros (sin truncar):");
            // 4. Mostrar una vista previa de los datos, como lo pediste
            dfFinal.show(20, false);

            System.out.println("✍️ Escribiendo datos en formato Parquet en la carpeta: " + PARQUET_OUTPUT_DIRECTORY);

            // 5. Guardar el DataFrame como Parquet
            dfFinal.write()
                   .mode("overwrite")
                   .parquet(PARQUET_OUTPUT_DIRECTORY);

            System.out.println("🏁 ¡Éxito! Archivo Parquet guardado en la carpeta: " + PARQUET_OUTPUT_DIRECTORY);

        } catch (Exception e) {
            System.err.println("❌ ERROR FATAL de Spark: Se ha producido una excepción.");
            // Imprimir el stack trace completo es CRUCIAL para el diagnóstico
            e.printStackTrace();
        } finally {
            // 6. Detener la sesión de Spark para liberar recursos
            if (spark != null) {
                spark.stop();
            }
        }
    }
}
