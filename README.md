# SmartChef AI API

API REST empresarial que convierte una **foto de tu despensa o refrigeradora** en
**3 recetas de aprovechamiento** personalizadas, usando IA multimodal
(**Google Gemini 1.5 Flash**) para detectar los ingredientes visibles.

Incluye una **SPA integrada** (drag & drop, vista previa, tarjetas de receta) y
documentación interactiva con **Swagger UI**.

| | |
|---|---|
| Java | 21 |
| Framework | Spring Boot 3.3.4 |
| Base de datos | PostgreSQL (H2 en memoria para pruebas) |
| IA | Google Gemini 1.5 Flash (REST, `response_mime_type: application/json`) |
| Seguridad | Spring Security + JWT (HMAC-SHA, 24 h) + BCrypt |
| Docs | springdoc-openapi 2.6.0 → `/swagger-ui.html` |
| Despliegue | Dockerfile multi-stage, compatible con Render |

---

## Arranque rápido

### 1. Requisitos

- JDK 21
- PostgreSQL en marcha (o ajusta `DATABASE_URL`)
- Una API key de Gemini: https://aistudio.google.com/apikey

### 2. Variables de entorno

Copia `.env.example` y exporta los valores (sólo `GEMINI_API_KEY` es imprescindible):

```bash
export GEMINI_API_KEY=tu-api-key
export DATABASE_URL=jdbc:postgresql://localhost:5432/smartchef
export DATABASE_USERNAME=postgres
export DATABASE_PASSWORD=postgres
export JWT_SECRET=<cadena-base64-de-al-menos-32-bytes>
```

En PowerShell:

```powershell
$env:GEMINI_API_KEY = "tu-api-key"
```

### 3. Compilar y probar

```bash
./mvnw clean package
```

Las pruebas se ejecutan **100 % offline**: Gemini se simula con `@MockBean` y la
base de datos es H2 en memoria, por lo que no se necesita API key ni PostgreSQL
para que `./mvnw test` pase.

### 4. Ejecutar

```bash
./mvnw spring-boot:run
```

| Recurso | URL |
|---|---|
| SPA | http://localhost:8080/ |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| Health | http://localhost:8080/actuator/health |

---

## Endpoints

Base: `/api/v1`

### Autenticación

| Método | Ruta | Auth | Descripción |
|---|---|---|---|
| `POST` | `/auth/register` | — | Registro con email validado y contraseña BCrypt. Devuelve JWT. |
| `POST` | `/auth/login` | — | Acepta username **o** email. Devuelve JWT válido 24 h. |
| `GET` | `/auth/me` | JWT | Perfil del portador del token. |

### Recetas con IA

| Método | Ruta | Auth | Descripción |
|---|---|---|---|
| `POST` | `/recipes/generate-from-image` | Opcional | `multipart/form-data` con el campo `image`. Analiza la foto y devuelve ingredientes + 3 recetas. Con JWT, el escaneo se guarda en tu historial. |
| `GET` | `/recipes/history` | JWT | Historial de escaneos del usuario, más recientes primero. |
| `POST` | `/recipes/{id}/favorite` | JWT | Alterna la receta como favorita. |
| `GET` | `/recipes/favorites` | JWT | Recetas guardadas en favoritos. |

### Ejemplo

```bash
curl -X POST http://localhost:8080/api/v1/recipes/generate-from-image \
  -F "image=@mi-refrigeradora.jpg"
```

```json
{
  "scanSessionId": 1,
  "detectedIngredients": ["tomate", "huevo", "cebolla", "queso"],
  "recipes": [
    {
      "id": 1,
      "title": "Tortilla campesina de tomate",
      "description": "Receta de aprovechamiento con lo que ya tienes.",
      "prepTimeMinutes": 20,
      "difficulty": "FACIL",
      "caloriesEstimate": 380,
      "steps": ["Batir los huevos", "Saltear el tomate", "Cuajar la tortilla"],
      "ingredients": [
        { "name": "tomate", "quantity": "2 unidades", "available": true },
        { "name": "albahaca fresca", "quantity": "1 ramita", "available": false }
      ],
      "favorite": false
    }
  ],
  "createdAt": "2026-10-15T15:50:16.323Z"
}
```

Guardar una receta en favoritos:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"chef_ana","password":"SuperSecreta123"}' | jq -r .token)

curl -X POST http://localhost:8080/api/v1/recipes/1/favorite \
  -H "Authorization: Bearer $TOKEN"
```

---

## Salvaguardas de estabilidad

1. **Fotos de celular (hasta 15 MB).** `max-file-size: 15MB` / `max-request-size: 20MB`.
   Si la imagen supera 1 MB, `ImageProcessingService` la redimensiona en memoria con
   `BufferedImage` / `ImageIO` a 1024 px de ancho antes de pasarla a Base64; esto recorta
   la latencia de la llamada a Gemini de ~5 s a ~1,5 s. Si `ImageIO` no sabe decodificar el
   formato (p. ej. WEBP sin plugin), se envían los bytes originales en lugar de fallar.

2. **JSON estricto de Gemini.** La petición incluye
   `generationConfig.response_mime_type: application/json` y `temperature: 0.4`.
   Además, `stripMarkdownFences()` elimina de forma defensiva los bloques de código
   markdown y el texto conversacional antes de entregar el contenido a Jackson.
   `Difficulty.fromText()` normaliza variantes con acentos, en inglés o en minúsculas.

3. **Pruebas aisladas y offline.** `src/test/resources/application-test.yml` usa H2 con
   `ddl-auto: create-drop` y `spring.sql.init.mode: never`; las pruebas de integración
   sustituyen `GeminiVisionService` con `@MockBean`. No se requiere API key ni red.

4. **UTF-8 estricto.** Fuentes, `server.servlet.encoding` forzado, respuestas declaradas
   como `application/json;charset=UTF-8` y una prueba que verifica la ausencia de mojibake.

5. **Compatibilidad con Render.** `server.port: ${PORT:8080}`, `Dockerfile` multi-stage
   (Maven → Temurin 21 JRE Alpine, usuario sin privilegios) y `render.yaml` listo con la
   base de datos PostgreSQL y el health check.

6. **Manejo de errores homogéneo.** `GlobalExceptionHandler` traduce cada fallo a un JSON
   estable: `400` (validación, formato de imagen, parámetro ausente), `401` (credenciales
   o token), `404`, `409` (duplicados), `413` (imagen > 15 MB) y `503` (IA no disponible).

---

## Modelo de datos

```
users (id, username, email, password_hash, full_name, role, created_at)
  └─ scan_sessions (id, user_id NULL, detected_ingredients_summary, created_at)
       └─ recipes (id, scan_session_id, title, description, prep_time_minutes,
                   difficulty, calories_estimate, steps_json, created_at)
            └─ recipe_ingredients (id, recipe_id, name, is_available, quantity)
  └─ favorite_recipes (user_id, recipe_id, saved_at)   [PK compuesta]
```

`user_id` en `scan_sessions` es nullable a propósito: el escaneo funciona sin iniciar sesión.

Hibernate gestiona el DDL (`JPA_DDL_AUTO`, por defecto `update`). `src/main/resources/schema.sql`
contiene el esquema explícito de PostgreSQL y se aplica sólo si pones `SQL_INIT_MODE=always`.

---

## Docker

```bash
docker build -t smartchef-ai-api .
docker run -p 8080:8080 \
  -e GEMINI_API_KEY=tu-api-key \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/smartchef \
  -e DATABASE_USERNAME=postgres \
  -e DATABASE_PASSWORD=postgres \
  smartchef-ai-api
```

## Despliegue en Render

1. Sube el repositorio a GitHub.
2. En Render: **New → Blueprint** y selecciona el repo (usa `render.yaml`).
3. Define `GEMINI_API_KEY` como variable secreta; `JWT_SECRET` se genera automáticamente
   y las credenciales de PostgreSQL se enlazan desde la base de datos del blueprint.

---

## Estructura del proyecto

```
src/main/java/com/smartchef/api/
├── config/      GeminiProperties, JwtProperties, ImageProperties,
│                SecurityConfig, RestClientConfig, OpenApiConfig
├── controller/  AuthController, RecipeController
├── dto/         Peticiones, respuestas y el contrato JSON de Gemini
├── entity/      User, ScanSession, Recipe, RecipeIngredient, FavoriteRecipe, enums
├── exception/   Excepciones de dominio + GlobalExceptionHandler
├── repository/  Repositorios Spring Data JPA
├── security/    JwtService, JwtAuthenticationFilter, UserDetails
└── service/     ImageProcessingService, GeminiVisionService, AuthService, RecipeService

src/main/resources/
├── application.yml
├── schema.sql
└── static/      index.html, styles.css, app.js   (SPA)

src/test/
├── java/...     GeminiVisionServiceTest, ImageProcessingServiceTest,
│                JwtServiceTest, DifficultyTest, SmartChefApiIntegrationTest
└── resources/   application-test.yml  (H2 en memoria)
```

## Pruebas

```bash
./mvnw test
```

- `GeminiVisionServiceTest` — JSON limpio, con fences markdown, con texto previo, JSON
  inválido, error de la API, fallo de red y API key ausente.
- `ImageProcessingServiceTest` — formatos aceptados y rechazados, redimensionado a 1024 px
  manteniendo la relación de aspecto, normalización de MIME, fallback de decodificación.
- `JwtServiceTest` — emisión, validación, expiración, firma distinta, secreto no Base64.
- `DifficultyTest` — normalización de las variantes que puede devolver el modelo.
- `SmartChefApiIntegrationTest` — flujo completo sobre H2: SPA, OpenAPI, registro, login,
  duplicados, validaciones, escaneo anónimo y autenticado, historial por usuario,
  favoritos (alternar ida y vuelta), UTF-8 y todos los códigos de error.
