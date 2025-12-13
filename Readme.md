# Arquitectura Técnica para la Extracción y Filtrado de Localización de Audio en el Ecosistema Steam mediante Java

## 1. Introducción y Contexto del Problema de Datos
La plataforma Steam, desarrollada y operada por Valve Corporation, se ha consolidado como el repositorio hegemónico de distribución digital de videojuegos para PC, albergando un catálogo que supera los 190.000 títulos y aplicaciones a fecha de 2025. Para los desarrolladores de software, investigadores de mercado y entusiastas que buscan construir herramientas de valor añadido sobre este vasto océano de datos, el desafío principal no reside en la inexistencia de la información, sino en la fragmentación arquitectónica de las interfaces de acceso que Valve proporciona.

La solicitud que motiva este informe técnico —la creación de una aplicación en Java capaz de identificar y filtrar videojuegos que dispongan específicamente de doblaje de voz al español— aborda una carencia funcional crítica en la interfaz de usuario nativa de la tienda de Steam. Aunque Valve recolecta y almacena meticulosamente los metadatos lingüísticos, distinguiendo entre interfaz, subtítulos y "audio completo" (doblaje), la funcionalidad de búsqueda y filtrado de la tienda no expone el parámetro de "audio" como un criterio de inclusión o exclusión independiente.¹ Actualmente, un usuario puede filtrar juegos que "soporten español", pero este filtro agrupa indistintamente aquellos que solo tienen menús traducidos, aquellos con subtítulos y aquellos con doblaje integral. Para un jugador que busca específicamente la experiencia inmersiva del audio en su idioma nativo, la herramienta de búsqueda oficial resulta insuficiente y genera falsos positivos masivos.³

Este informe despliega una investigación exhaustiva y una propuesta arquitectónica detallada para resolver este problema mediante ingeniería de software. La solución implica un enfoque de **"fuerza bruta controlada"**: la adquisición de la totalidad del catálogo de aplicaciones de Steam y la interrogación secuencial de los detalles de cada ítem para analizar sintácticamente sus capacidades lingüísticas. Dado que no existe un endpoint único que devuelva "Juegos con audio en español", la aplicación debe construir su propio índice local (el archivo JSON solicitado) mediante la orquestación de múltiples APIs de Valve.

El desarrollo de esta solución en Java presenta desafíos técnicos significativos que serán desglosados en profundidad: la gestión de la paginación sobre conjuntos de datos masivos, la implementación de algoritmos de limitación de tasa (*rate limiting*) para cumplir con las estrictas cuotas de tráfico de la API de la tienda (*Storefront API*), el análisis de cadenas HTML no estructuradas mediante expresiones regulares para detectar los marcadores de audio, y la optimización del manejo de memoria para la serialización de archivos JSON de gran volumen. A través de este documento, se establecerá el "camino correcto" —basado en la documentación más reciente y la ingeniería inversa comunitaria— para interactuar con la infraestructura de Valve, descartando métodos obsoletos y adoptando patrones de diseño robustos y escalables.

## 2. Anatomía de las Interfaces de Programación (API) de Valve
Para diseñar una estrategia de extracción de datos eficaz, es imperativo comprender la dualidad del ecosistema de APIs de Steam. A diferencia de plataformas modernas con una API Graph unificada, Steam opera con una amalgama de servicios heredados y modernos que sirven a propósitos distintos. La confusión entre estos servicios es la causa principal de fallos en aplicaciones de terceros.

### 2.1 La Dicotomía: Steamworks Web API vs. Storefront API
El ecosistema se divide fundamentalmente en dos categorías de interfaces, cada una con sus propias reglas de autenticación, cuotas de uso y estructuras de datos.

#### 2.1.1 Steamworks Web API: La Capa de Servicio
La `Steamworks Web API` es la interfaz oficial, documentada y orientada a servicios. Su propósito es permitir que los desarrolladores y editores interactúen con los sistemas de backend de Steam.
- **Autenticación**: Requiere estrictamente una Clave de API (*API Key*) que se obtiene a través del panel de desarrolladores de la comunidad.⁵
- **Protocolo**: Sigue estándares RESTful, devolviendo respuestas en formato JSON, XML o VDF.⁶
- **Fiabilidad**: Alta. Posee contratos de servicio estables y versionados (v1, v2).
- **Limitación**: Su mayor debilidad para el propósito de este proyecto es que los endpoints "públicos" de esta API suelen devolver datos de alto nivel (listas de servidores, noticias, logros globales) pero carecen de la granularidad de los metadatos de la tienda (descripciones, precios, y crucialmente, detalles de idiomas soportados).⁵

#### 2.1.2 Storefront API: La Capa de Presentación
La `Storefront API` actúa como el backend directo de la página web `store.steampowered.com`. No es una API pública oficial en el sentido estricto de "producto para desarrolladores", sino una interfaz interna expuesta públicamente que alimenta la renderización dinámica de la tienda.
- **Autenticación**: No requiere API Key para consultar datos públicos de juegos. Es accesible de manera anónima.⁷
- **Contenido**: Es la única fuente que proporciona el objeto `supported_languages` detallado, que incluye la distinción crítica entre texto y audio.⁹
- **Riesgo y Control**: Al ser una API diseñada para navegación web y no para extracción masiva, Valve implementa medidas agresivas de limitación de tasa (*throttling*) y bloqueo por IP para prevenir el "scraping" abusivo.¹¹ La violación de estos límites resulta en baneos temporales (HTTP 429/403).

### 2.2 Evolución Histórica: La Deprecación de ISteamApps
Uno de los errores más comunes al iniciar un proyecto de esta naturaleza es basarse en documentación antigua que referencia la interfaz `ISteamApps`. Históricamente, el endpoint `GET https://api.steampowered.com/ISteamApps/GetAppList/v2/` era el método estándar para obtener la lista completa de juegos. Este endpoint devolvía un único objeto JSON monolítico conteniendo todo el catálogo.¹³

Sin embargo, a medida que Steam abrió sus puertas a juegos independientes y el catálogo creció de unos pocos miles a cientos de miles de entradas, este endpoint dejó de ser viable. La respuesta JSON se volvió tan masiva que causaba tiempos de espera agotados (*timeouts*) y problemas de memoria tanto en los servidores de Valve como en los clientes consumidores.

La documentación oficial de Valve ahora marca explícitamente este método como *obsoleto* (*deprecated*), advirtiendo que "esta API ya no puede escalar al número de ítems disponibles en Steam".¹³ El uso continuado de `ISteamApps` no solo es ineficiente, sino que presenta el riesgo de devolver datos incompletos o truncados, lo que comprometería la integridad de la base de datos de juegos que el usuario desea construir.

### 2.3 El Estándar Moderno: IStoreService
La investigación confirma que la manera correcta y moderna de obtener la lista de aplicaciones es a través de la interfaz `IStoreService`. Esta interfaz fue diseñada para solucionar los problemas de escalabilidad mediante la introducción de paginación y filtrado en el lado del servidor.¹⁵

El método específico es `GetAppList` (v1) bajo la interfaz `IStoreService`. A diferencia de su predecesor, este método no devuelve todo el catálogo de una vez. En su lugar, obliga al cliente a iterar a través del catálogo en bloques manejables, utilizando un cursor lógico basado en el ID de la aplicación (`last_appid`).

| Característica | ISteamApps/GetAppList (Obsoleto) | IStoreService/GetAppList (Correcto) |
| :--- | :--- | :--- |
| **Escalabilidad** | Baja (Falla con catálogos grandes) | Alta (Diseñado para >100k ítems) |
| **Método de Entrega** | Carga útil única (Monolito) | Paginación por cursor |
| **Filtrado** | Inexistente (Devuelve todo) | Granular (Juegos, DLC, Hardware) |
| **Autenticación** | Opcional | Requiere API Key |
| **Estabilidad** | Sin soporte oficial | Soporte activo |

El uso de `IStoreService` permite al desarrollador aplicar filtros preliminares críticos. Dado que el objetivo del usuario es filtrar "juegos" con voces, no tiene sentido descargar y procesar los metadatos de miles de DLCs (contenido descargable), bandas sonoras o videos. `IStoreService` permite excluir estos tipos de aplicaciones desde la raíz, reduciendo drásticamente el volumen de llamadas necesarias en la segunda fase del proceso.¹⁶

## 3. Ingeniería de la Fase 1: Adquisición del Catálogo Global
La primera etapa de la aplicación Java, que llamaremos "El Recolector" (*The Harvester*), tiene como único objetivo generar una lista maestra de identificadores (AppIDs) que correspondan a videojuegos. Esta lista servirá como la cola de trabajo para el proceso de enriquecimiento de datos subsiguiente.

### 3.1 Configuración del Cliente HTTP en Java
Para interactuar con `IStoreService`, la aplicación Java requiere un cliente HTTP robusto. Aunque Java 11 introdujo el moderno `java.net.http.HttpClient`, en el ecosistema profesional de Java a menudo se prefiere `OkHttp` de Square por su manejo eficiente de conexiones, interceptores y recuperación ante fallos de red. Alternativamente, `Apache HttpClient` es otra opción estándar. Para este informe, asumiremos un diseño agnóstico, pero enfatizaremos la importancia de configurar tiempos de espera (*timeouts*) adecuados, ya que la API de Steam puede tener latencia variable.

La petición debe dirigirse a:
`https://api.steampowered.com/IStoreService/GetAppList/v1/`

### 3.2 Parámetros de la Petición y Lógica de Paginación
Para construir la lista completa, el algoritmo en Java debe implementar un bucle `while` que continúe solicitando páginas hasta que la API indique que no hay más resultados.

Los parámetros obligatorios y recomendados son:
- `key`: La clave de API del usuario. Sin ella, `IStoreService` denegará el acceso (HTTP 401/403).¹⁶
- `include_games=true`: Asegura explícitamente la inclusión de juegos base.
- `include_dlc=false`: Esta es una optimización crítica. El catálogo de Steam está inflado por decenas de miles de DLCs. Al establecer esto en `false`, eliminamos ruido innecesario, ahorrando ancho de banda y tiempo de procesamiento futuro.¹⁵
- `include_software=false`, `include_videos=false`, `include_hardware=false`: Exclusión de utilidades, películas y hardware (como el Steam Deck o Valve Index), irrelevantes para la búsqueda de doblaje en juegos.¹⁷
- `max_results=50000`: Aunque el defecto es 10.000, la documentación permite hasta 50.000. Solicitar el máximo reduce el número total de "handshakes" HTTP necesarios para recuperar el catálogo completo. Si el catálogo tiene 150.000 juegos, esto reduce las llamadas de 15 a solo 3 o 4.¹⁵
- `last_appid`: Este es el cursor. En la primera llamada, se omite o se pone en 0. En las llamadas subsiguientes, se debe pasar el valor numérico del último `appid` recibido en la respuesta anterior.

#### Pseudocódigo de Implementación en Java
```java
// Estructura conceptual del bucle de recolección
List<Integer> masterAppIdList = new ArrayList<>();
int lastAppId = 0;
boolean hasMore = true;

while (hasMore) {
    // Construcción de la URL con el cursor
    String url = "https://api.steampowered.com/IStoreService/GetAppList/v1/?" +
                 "key=" + API_KEY +
                 "&include_games=true" +
                 "&include_dlc=false" +
                 "&max_results=50000" +
                 "&last_appid=" + lastAppId;

    // Ejecución de la llamada HTTP (ejemplo conceptual)
    Response response = httpClient.execute(url);
    JsonNode root = jsonParser.parse(response.body());
    
    // Extracción de la lista de apps
    JsonNode appsNode = root.path("response").path("apps");
    
    if (appsNode.isEmpty()) {
        hasMore = false; // Condición de salida
    } else {
        for (JsonNode app : appsNode) {
            masterAppIdList.add(app.get("appid").asInt());
            // Actualizar el cursor al ID actual (la lista viene ordenada)
            lastAppId = app.get("appid").asInt();
        }
        
        // Verificación de seguridad: si el número de apps es menor al solicitado,
        // es probable que sea la última página.
    }
}
```
