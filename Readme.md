# Steam Data Scraper & Parquet Converter

Este proyecto es una herramienta robusta desarrollada en Java para descargar metadatos de juegos de la API de Steam, guardarlos en un formato JSON y, finalmente, convertirlos a un archivo Parquet altamente eficiente.

El objetivo principal es crear una base de datos de juegos que pueda ser actualizada periódicamente y utilizada por otras aplicaciones (como una app móvil en Flutter) de forma rápida y con un coste cero, alojando el archivo Parquet final en GitHub.

## ✨ Características Principales

- **Scraping Inteligente:** El script detecta automáticamente los juegos ya descargados. En ejecuciones posteriores, solo busca y añade los lanzamientos más recientes, deteniéndose de forma autónoma cuando la base de datos está al día.
- **Recuperación ante Fallos:** Si el proceso se interrumpe (por un corte de red, un error, etc.), el script guarda el progreso y reanuda exactamente donde lo dejó la próxima vez que se ejecute.
- **Caché de IDs:** La lista completa de AppIDs de Steam (que es muy larga) se descarga una sola vez y se guarda en una caché local (`lista_appids.txt`) para que los arranques posteriores sean casi instantáneos.
- **Conversión a Parquet:** Incluye una clase dedicada (`JsonToParquet.java`) para convertir el archivo JSON final a formato Parquet. Parquet es un formato columnar, comprimido y optimizado, ideal para consultas rápidas y para reducir el tamaño del archivo.
- **Gestión de Dependencias con Gradle:** Utiliza Gradle para gestionar de forma automática todas las librerías necesarias (Jackson para JSON, Apache Parquet, Avro y Hadoop).

## 📂 Estructura del Proyecto

- `SteamScraper.java`: La clase principal que se encarga de conectar con la API de Steam, descargar los datos de los juegos y guardarlos en `juegos_nuevos.json`.
- `JsonToParquet.java`: La clase que lee `juegos_nuevos.json` y lo convierte a `SteamGames.parquet`.
- `build.gradle`: El archivo de configuración de Gradle que define las dependencias del proyecto.
- `gradle.properties`: Archivo de configuración para asegurar la compatibilidad con la versión correcta de Java.

## 🚀 Cómo Usar el Proyecto

### Prerrequisitos
- **JDK 17:** El proyecto está configurado para usar Java 17. Asegúrate de tenerlo instalado y configurado en tu IDE.
- **IDE:** Se recomienda IntelliJ IDEA o Android Studio.

### Pasos para la Ejecución

1.  **Clonar el Repositorio:**
    ```bash
    git clone https://github.com/andymartin1991/SteamDataScraper.git
    ```

2.  **Abrir en el IDE:**
    Abre la carpeta del proyecto con tu IDE. Debería detectar automáticamente que es un proyecto Gradle.

3.  **Sincronizar Gradle:**
    La primera vez que abras el proyecto, el IDE necesitará descargar las librerías definidas en `build.gradle`. Busca el icono de Gradle (un elefante) y haz clic en "Reload All Gradle Projects" o utiliza la opción del menú `File > Sync Project with Gradle Files`.

4.  **Ejecutar el Scraper:**
    - Abre el archivo `src/SteamScraper.java`.
    - Haz clic derecho en el editor y selecciona `Run 'SteamScraper.main()'`.
    - **Nota:** La primera ejecución será muy larga, ya que tiene que construir la base de datos desde cero. Las siguientes ejecuciones para buscar actualizaciones serán muy rápidas.

5.  **Convertir a Parquet:**
    - Una vez que el scraper haya terminado, abre `src/JsonToParquet.java`.
    - Haz clic derecho y selecciona `Run 'JsonToParquet.main()'`.
    - Esto generará un archivo `SteamGames.parquet` en la raíz del proyecto.

### Actualizar la Base de Datos en GitHub

El archivo `SteamGames.parquet` se genera en tu ordenador. Para que tu app de Flutter pueda acceder a él, necesitas subirlo a tu repositorio de GitHub.

1.  Abre la terminal en la raíz del proyecto.
2.  Ejecuta los siguientes comandos de Git:

    ```bash
    # Añade todos los cambios (incluido el nuevo archivo Parquet)
    git add .

    # Guarda los cambios con un mensaje descriptivo
    git commit -m "Actualizada la base de datos Parquet con los últimos juegos"

    # Sube los cambios a GitHub
    git push
    ```
¡Y listo! Tu base de datos estará actualizada y disponible en el repositorio.
