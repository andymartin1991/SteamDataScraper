# Steam Data Scraper & Parquet Converter

Este proyecto es una herramienta robusta desarrollada en Java para descargar metadatos de juegos de la API de Steam, guardarlos en un formato JSON y, finalmente, convertirlos a un dataset Parquet altamente eficiente.

El objetivo principal es crear una base de datos de juegos que pueda ser actualizada periódicamente y utilizada por otras aplicaciones (como una app móvil en Flutter) de forma rápida y con un coste cero, alojando los datos finales en GitHub.

## ✨ Características Principales

- **Scraping Inteligente:** El script detecta automáticamente los juegos ya descargados. En ejecuciones posteriores, solo busca y añade los lanzamientos más recientes, deteniéndose de forma autónoma (tras detectar 50 juegos repetidos) cuando la base de datos está al día.
- **Recuperación ante Fallos:** Si el proceso se interrumpe (por un corte de red, un error, etc.), el script guarda el progreso y reanuda exactamente donde lo dejó la próxima vez que se ejecute.
- **Caché de IDs:** La lista completa de AppIDs de Steam se descarga y guarda en una caché local (`lista_appids.txt`). Si se inicia una nueva búsqueda completa, esta caché se renueva automáticamente para incluir los últimos lanzamientos.
- **Conversión a Parquet:** Incluye una clase dedicada (`JsonToParquet.java`) para convertir el archivo JSON final a formato Parquet. Parquet es un formato columnar, comprimido y optimizado, ideal para consultas rápidas y reducir el tamaño de los datos.
- **Compatibilidad Windows:** El proyecto incluye los binarios necesarios (`winutils`) para ejecutar Apache Spark en Windows sin configuraciones complejas de Hadoop.

## 📂 Estructura del Proyecto

- `src/SteamScraper.java`: Clase principal que conecta con la API de Steam, descarga los datos y los guarda en `juegos_nuevos.json`.
- `src/JsonToParquet.java`: Clase que lee `juegos_nuevos.json` y lo convierte a `SteamGames.parquet` usando Apache Spark.
- `bin/`: Contiene utilidades nativas de Hadoop (`winutils.exe`, `hadoop.dll`) requeridas para que Spark funcione correctamente en Windows.
- `build.gradle`: Configuración de Gradle con las dependencias (Jackson, Spark, Hadoop) y tareas personalizadas.
- `juegos_nuevos.json`: Archivo intermedio donde se acumulan los datos crudos en JSON.
- `SteamGames.parquet`: Carpeta de salida que contiene el dataset optimizado.

## 🚀 Cómo Usar el Proyecto

### Prerrequisitos
- **JDK 17:** El proyecto está configurado para Java 17.
- **IDE:** Recomendado IntelliJ IDEA o Android Studio.

### Pasos para la Ejecución

1.  **Clonar el Repositorio:**
    ```bash
    git clone https://github.com/andymartin1991/SteamDataScraper.git
    ```

2.  **Abrir en el IDE:**
    Abre la carpeta del proyecto. El IDE debería detectar automáticamente que es un proyecto Gradle y descargar las dependencias.

3.  **Ejecutar el Scraper (Descarga de Datos):**
    Para iniciar la descarga o actualización de juegos, ejecuta la tarea principal:
    
    **Opción A (Gradle - Recomendada):**
    Ejecuta el siguiente comando en la terminal o busca la tarea `application > run` en el panel de Gradle:
    ```bash
    ./gradlew run
    ```
    
    **Opción B (Desde el Editor):**
    Abre `src/SteamScraper.java`, haz clic derecho y selecciona `Run 'SteamScraper.main()'`.

    > **Nota:** La primera ejecución tomará tiempo ya que construye la base de datos desde cero. Las ejecuciones futuras solo descargarán los juegos nuevos.

4.  **Convertir a Parquet:**
    Una vez finalizado el scraper, convierte los datos a Parquet.
    
    **Opción A (Gradle - Muy Recomendada):**
    Usa esta tarea específica que ya configura los argumentos de la JVM necesarios para Spark en Java 17+:
    ```bash
    ./gradlew runJsonToParquet
    ```
    (O busca la tarea `application > runJsonToParquet` en el panel de Gradle).

    **Opción B (Desde el Editor):**
    Si ejecutas `src/JsonToParquet.java` manualmente con clic derecho, es posible que necesites añadir la siguiente opción a la configuración de la VM (VM Options) para evitar errores de acceso en Java 17+:
    `--add-opens=java.base/sun.nio.ch=ALL-UNNAMED`

### Actualizar la Base de Datos en GitHub

La carpeta `SteamGames.parquet` se genera en tu ordenador local. Para hacerla accesible a tu aplicación:

1.  Abre la terminal en la raíz del proyecto.
2.  Ejecuta los comandos de Git:

    ```bash
    git add .
    git commit -m "Actualizada base de datos con últimos juegos"
    git push
    ```

¡Listo! Tu repositorio ahora aloja la versión más reciente de los datos optimizados.