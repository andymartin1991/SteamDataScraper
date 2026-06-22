# 🎮 Steam & RAWG Data Scraper (VoxGamer Backend)

Este proyecto es el backend de recolección y procesamiento de datos para la aplicación **VoxGamer**. Se encarga de descargar, unificar y exportar el catálogo completo de videojuegos de **Steam** y **RAWG**, generando una base de datos SQLite lista para ser consumida por la app móvil.

---

## 🚀 Características Principales

*   **Recolección Masiva:** Descarga cientos de miles de juegos de Steam y RAWG.
*   **Actualización Inteligente:**
    *   Detecta automáticamente juegos que pasan de "Próximamente" a "Lanzado".
    *   Revisa periódicamente juegos futuros para actualizar fechas.
    *   Optimización de API (Cooldowns) para no desperdiciar cuota en juegos lejanos.
*   **Fusión de Datos (GlobalUnion):**
    *   Unifica datos de ambas fuentes (Steam + RAWG).
    *   Resolución de conflictos por título y año.
    *   Fusión inteligente de metadatos (géneros, tiendas, idiomas, etc.).
*   **Formato Optimizado:** Exporta en **JSON Lines (NDJSON)** comprimido en GZIP para máxima eficiencia.
*   **Generador SQLite:** Crea un archivo `.sqlite` final con índices y tablas optimizadas, listo para la app.
*   **Reconexión Automática:** Los colectores de Steam y RAWG permanecen activos ante caídas de red, VPN, timeouts, rate limits y errores temporales del servidor.
*   **Recuperación tras cierres abruptos:** Cada escritor registra su ejecución. Si el proceso o el equipo se apagan inesperadamente, el siguiente arranque comprueba WAL, integridad SQLite y los JSON escritos antes de continuar.
*   **Diagnóstico de Exportación:** Cada scraper resume los elementos descartados por filtro y diferencia esos descartes de los errores reales de procesamiento.

### Política de reconexión

* Las desconexiones y timeouts se reintentan indefinidamente, con espera progresiva de 5 a 30 segundos.
* Los códigos `408`, `425` y `5xx` se reintentan indefinidamente; se respeta la cabecera `Retry-After` cuando existe.
* En Steam, los `429` también se reintentan respetando `Retry-After`.
* En RAWG, un `429` rota inmediatamente a la siguiente API key. Si todas devuelven `401` o `429`, el proceso termina para esperar a la renovación de cuota mensual.
* Al recuperar la conexión se informa en consola y la petición pendiente continúa, sin saltar el juego o la página.
* Los errores permanentes (`400`, `401`, `403`, `404`, etc.) no se reintentan como si fueran una caída. RAWG rota las claves ante un `401` y detiene el proceso si todas son rechazadas.
* Dos instancias del mismo collector no pueden escribir simultáneamente sobre la misma base. Un cierre limpio evita comprobaciones profundas innecesarias en el siguiente arranque.

---

## 🛠️ Arquitectura del Proyecto

### 1. Recolección (Collectors)
Estos scripts descargan los datos crudos de las APIs y los guardan en bases de datos intermedias (`data/db/steam_raw.sqlite`, `data/db/rawg_raw.sqlite`).

*   **`SteamRawCollector`**: Descarga el catálogo de Steam y solo consulta IDs nuevos.
*   **`RAWGRawCollector`**: Descarga el catálogo de RAWG por décadas.
    Cuando la carga masiva ya está completa, revisa por fecha de actualización hasta encontrar 1.000 juegos consecutivos sin cambios.
*   **`RAWGDetailCollector`**: Descarga detalles profundos (descripción, tiendas) de RAWG y deja la revisión de fechas al proceso dedicado.
*   **`SteamReleaseDateUpdater` / `RAWGReleaseDateUpdater`**: Revisan exclusivamente juegos sin fecha, TBA o futuros con esperas progresivas, sin recargar ese trabajo en los collectors principales.

### 2. Procesamiento (Scrapers)
Estos scripts leen las bases de datos crudas y generan archivos JSON intermedios limpios.

*   **`SteamScraper`**: Exporta juegos lanzados (Fecha <= HOY) en JSON Lines.
*   **`SteamUpcomingScraper`**: Exporta próximos lanzamientos (Fecha > HOY) en JSON Lines.
*   **`RAWGScraper`**: Exporta juegos lanzados de RAWG en JSON Lines.
*   **`RAWGUpcomingScraper`**: Exporta próximos lanzamientos de RAWG en JSON Lines.

### 3. Unificación (GlobalUnion)
*   **`GlobalUnion`**:
    *   Lee los JSONs de Steam y RAWG.
    *   Fusiona los juegos coincidentes.
    *   Genera `data/exports/global_games.json.gz` y `app_data/global_proximos_games.json.gz` en formato **JSON Lines**.

### 4. Exportación Final (SQLite)
La generación de `prebuilt_db/` se realiza con **otro proceso externo** a este repositorio.

---

## 📦 Cómo Ejecutar

### Requisitos
*   JDK 17 para ejecutar Gradle. El wrapper actual no es compatible con el JDK 25.
*   Conexión a Internet.
*   Claves de API configuradas fuera del código fuente.

### Configuración de claves API

No subas claves reales al repositorio. Para trabajar en local tienes tres opciones seguras.

**Opción recomendada:** copia `gradle-local.properties.example` como `gradle-local.properties` y rellena tus claves reales:

```properties
steam.api.key=tu_clave_steam
rawg.api.keys=clave_rawg_1,clave_rawg_2
```

`gradle-local.properties` está ignorado por Git, así que no se subirá al repositorio.

También puedes copiar `.env.example` como `.env`:

```dotenv
STEAM_API_KEY=tu_clave_steam
RAWG_API_KEYS=clave_rawg_1,clave_rawg_2
```

`.env` también está ignorado por Git.

Como alternativa, puedes configurar variables de entorno para la sesión actual:

```powershell
$env:STEAM_API_KEY = "tu_clave_steam"
$env:RAWG_API_KEYS = "clave_rawg_1,clave_rawg_2"
```

O pasarlas al proceso Java con `-Dsteam.api.key=...` y `-Drawg.api.keys=clave1,clave2`.

### Paso a Paso

1.  **Recolección de Datos:**
    ```bash
    ./gradlew runCollector          # Steam
    ./gradlew runRawgCollector      # RAWG (Lista)
    ./gradlew runRawgDetailCollector # RAWG (Detalles)
    ./gradlew runSteamReleaseDateUpdater # Steam (Fechas pendientes)
    ./gradlew runRawgReleaseDateUpdater  # RAWG (Fechas pendientes)
    ```

2.  **Procesamiento a JSON:**
    ```bash
    ./gradlew runScraper            # Steam Lanzados
    ./gradlew runRawgScraper        # RAWG Lanzados
    ./gradlew runSteamUpcomingScraper # Steam Próximos
    ./gradlew runRawgUpcomingScraper  # RAWG Próximos
    ```

3.  **Fusión Global:**
    ```bash
    ./gradlew runGlobalUnion
    # Seleccionar opción 1 (Global) y luego opción 2 (Próximos) en la consola.
    ```

4.  **Generación de SQLite:**
    *   Se realiza fuera de este repositorio.

---

## 📂 Estructura de Archivos Generados

### ✅ Consumidos por la app
*   `app_data/global_proximos_games.json.gz`
*   `prebuilt_db/` (generado por otro proceso; no se versionan GZIP grandes)

### 🧩 Intermedios (no app)
*   `data/db/steam_raw.sqlite` / `data/db/rawg_raw.sqlite`: Almacenamiento incremental de datos crudos.
*   `data/exports/global_games.json.gz`: Catálogo unificado de juegos lanzados (JSON Lines).
*   `app_data/global_proximos_games.json.gz`: Catálogo unificado de próximos lanzamientos (JSON Lines).
*   `prebuilt_db/`: Base de datos final para la app (generada fuera de este repo; no versionada si es GZIP grande).

**Otros intermedios:**
*   `data/exports/steam_games.json.gz`, `data/exports/rawg_games.json.gz`
*   `data/exports/steam_proximos_games.json.gz`, `data/exports/rawg_proximos_games.json.gz`
*   `data/reports/conflicts_report.txt`, `data/reports/conflicts_report_proximos.txt`

---

## 📝 Notas de Desarrollo

*   **JSON Lines:** Se usa este formato para permitir el procesamiento de archivos gigantes sin cargar todo el array en memoria.
*   **Jackson Streaming:** Se usa en el proceso externo que genera `prebuilt_db/`.
*   **Optimizaciones SQL:** Las inserciones se hacen en transacciones batch y los índices se crean al final para maximizar la velocidad de escritura.

---

**Autor:** VoxGamer Team
**Versión:** 2.0 (Arquitectura Híbrida Steam+RAWG)
