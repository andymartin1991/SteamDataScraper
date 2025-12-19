# 🎮 VoxGamer Data Sync Engine

Este proyecto es el motor de sincronización de datos para **VoxGamer**. Su función es recolectar, filtrar y estandarizar el catálogo de juegos de múltiples ecosistemas (PC y consolas) para crear una base de datos unificada.

## 🏗️ Arquitectura Multi-Fuente (ELT)

El sistema utiliza una arquitectura ELT (Extract, Load, Transform) modular, donde cada fuente de datos tiene su propio pipeline de recolección y procesado, finalizando en una etapa de unificación global.

### Fuentes de Datos
1.  **Steam (PC):** A través de la API oficial de Steam.
2.  **RAWG (Consolas):** A través de la API de RAWG para PlayStation, Xbox y Nintendo.

---

## ⚙️ Pipeline de Steam

### 1. `SteamRawCollector` (Extracción y Carga)
Crea una copia local y robusta de los datos de Steam en `steam_raw.sqlite`.
- **Filtro Inteligente:** Descarga el catálogo completo y distingue entre juegos, DLCs y otro software, guardando los no-juegos en `steam_ignored_ids`.
- **Actualización de "Coming Soon":** Vuelve a verificar juegos que aún no han sido lanzados para detectar su fecha de salida.

### 2. `SteamScraper` (Transformación)
Lee `steam_raw.sqlite` y genera un archivo `steam_games.json.gz` con un formato de datos universal.
- **Estandarización:** Normaliza fechas, genera slugs, limpia descripciones y extrae datos clave como idiomas, Metacritic y requisitos de almacenamiento.
- **Formato Final:** Añade el campo `"plataformas": ["PC"]` para la unificación.

---

## 🎮 Pipeline de RAWG (Consolas)

### 1. `RAWGRawCollector` (Extracción y Carga)
Descarga el catálogo de juegos para las plataformas de consola seleccionadas (PS5, Xbox Series, Switch) y lo guarda en `rawg_raw.sqlite`.
- **Optimización:** Ordena los resultados por fecha de actualización y utiliza una estrategia de "parada temprana" para hacer las sincronizaciones diarias extremadamente rápidas.
- **Robustez:** Reintenta automáticamente las peticiones si la API de RAWG devuelve errores temporales (ej. 502).

### 2. `RAWGDetailCollector` (Enriquecimiento Inteligente)
Este script enriquece los datos de `rawg_raw.sqlite` de forma eficiente, distinguiendo entre juegos nuevos y existentes.
- **Modo Inteligente:** Identifica qué juegos necesitan ser procesados:
    - **Juegos Nuevos:** Aquellos que no tienen ninguna entrada en la tabla `rawg_details_data`.
    - **Juegos a Actualizar:** Aquellos que ya tienen la ficha de detalle pero les falta la información de tiendas (`json_stores` es nulo).
- **Proceso de Enriquecimiento:**
    - Para **juegos nuevos**, descarga tanto la ficha de detalle (`/games/{id}`) como los enlaces a tiendas (`/games/{id}/stores`).
    - Para **juegos a actualizar**, solo descarga la información de las tiendas, ahorrando tiempo y llamadas a la API.
- **Manejo de Errores:** Si un juego devuelve un error 404 (no encontrado), lo marca internamente para no volver a intentarlo en futuras ejecuciones.

### 3. `RAWGScraper` (Transformación)
Lee `rawg_raw.sqlite` (ambas tablas, `rawg_raw_data` y `rawg_details_data`) y genera `rawg_games.json.gz`.
- **Fusión de Datos:** Combina la información básica de la lista con los datos enriquecidos de detalle y tiendas.
- **Lógica de Tiendas Mejorada:**
    - **Prioriza URL Directa:** Si la columna `json_stores` contiene datos, extrae de ahí la URL final de la tienda.
    - **Fallback a Búsqueda:** Si `json_stores` está vacío (para datos antiguos o si la API falló), genera una URL de búsqueda genérica como antes.
- **Inferencia de Datos:**
    - Deduce si un juego es `"is_free": true` buscando el tag "Free to Play".
    - Infiere la plataforma "PC" si el juego se vende en tiendas como Steam, Epic o GOG.

---

## 🌍 Unificación Global

### `GlobalUnion` (Fusión Final)
Esta es la etapa final del proceso. Toma los archivos `steam_games.json.gz` y `rawg_games.json.gz` y los fusiona en un único archivo maestro: `global_games.json.gz`.

**Lógica de Fusión:**
1.  **Carga en Memoria:** Carga todos los juegos de Steam en un mapa para acceso rápido.
2.  **Iteración y Cruce:** Recorre los juegos de RAWG uno a uno.
    *   **Si el juego existe en Steam (Coincidencia por Slug):**
        *   Toma los datos de Steam como base (más fiables para PC).
        *   **Enriquece:** Añade plataformas, géneros y galerías de RAWG que no estén en Steam.
        *   **Tiendas:** Añade enlaces a tiendas de consola (PS Store, eShop) provenientes de RAWG.
        *   **Metacritic:** Se queda con la puntuación más alta de las dos fuentes.
    *   **Si el juego NO existe en Steam:**
        *   Añade el juego de RAWG tal cual (exclusivo de consola).
3.  **Completado:** Finalmente, añade todos los juegos de Steam que no fueron cruzados (exclusivos de PC).

---

## 🚀 Cómo Ejecutar

### Requisitos
*   Java 17 (Amazon Corretto recomendado).
*   Gradle.

### Ejecución
Puedes ejecutar cada fase de forma independiente usando las tareas de Gradle. El orden recomendado es:

1.  **Recolectar Datos:**
    *   `runCollector` (para Steam)
    *   `runRawgCollector` (para consolas)
    *   `runRawgDetailCollector` (para enriquecer datos de consolas)

2.  **Procesar y Generar JSONs Parciales:**
    *   `runScraper` (para Steam)
    *   `runRawgScraper` (para consolas)

3.  **Unificación Final:**
    *   `runGlobalUnion` (Genera `global_games.json.gz`)

O desde la terminal:
```bash
# 1. Recolección
./gradlew runCollector
./gradlew runRawgCollector
./gradlew runRawgDetailCollector

# 2. Procesado
./gradlew runScraper
./gradlew runRawgScraper

# 3. Unificación
./gradlew runGlobalUnion
```

## 📂 Estructura de Datos (SQLite y JSON)

*   **`steam_raw.sqlite`**: Datos crudos de Steam.
*   **`rawg_raw.sqlite`**: Datos crudos de RAWG (lista + detalles + tiendas).
*   **`steam_games.json.gz`**: Catálogo procesado de Steam.
*   **`rawg_games.json.gz`**: Catálogo procesado de RAWG.
*   **`global_games.json.gz`**: **Archivo Maestro Final** con todos los juegos unificados.

## 🛠️ Tecnologías
*   **Java 17**
*   **SQLite**
*   **Jackson (JSON Processing)**
*   **GZIP**
*   **Gradle**

---
*VoxGamer Data Engineering Team*
