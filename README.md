# 🍳 SmartChef AI API

[![Live Demo](https://img.shields.io/badge/Demo-Live%20on%20Render-brightgreen?style=for-the-badge&logo=render)](https://smartchef-ai-api.onrender.com/)
[![Swagger UI](https://img.shields.io/badge/API_Docs-Swagger_UI-blue?style=for-the-badge&logo=swagger)](https://smartchef-ai-api.onrender.com/swagger-ui.html)
[![Java 21](https://img.shields.io/badge/Java-21-orange?style=for-the-badge&logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.3.4-6DB33F?style=for-the-badge&logo=springboot)](https://spring.io/projects/spring-boot)
[![Tests](https://img.shields.io/badge/Tests-74%20passed%20(100%25)-success?style=for-the-badge&logo=junit5)](https://github.com/MrOdar/smartchef-ai-api)

> API REST empresarial y Single Page Application (SPA) que convierte una **foto de tu despensa o refrigeradora** en **3 recetas de aprovechamiento** personalizadas, utilizando IA multimodal (**Google Gemini Vision**) para detectar los ingredientes visibles con tolerancia a fallos y conmutación automática de modelos.

---

## 🚀 Acceso en Producción

| Recurso | Enlace Directo | Descripción |
|---|---|---|
| 🌐 **Aplicación Web (SPA)** | [smartchef-ai-api.onrender.com](https://smartchef-ai-api.onrender.com/) | Interfaz web interactiva con arrastrar y soltar, historial y favoritos |
| 📖 **Documentación Swagger UI** | [smartchef-ai-api.onrender.com/swagger-ui.html](https://smartchef-ai-api.onrender.com/swagger-ui.html) | OpenAPI 3.0 interactivo para probar endpoints |
| 📊 **Especificación OpenAPI** | [smartchef-ai-api.onrender.com/v3/api-docs](https://smartchef-ai-api.onrender.com/v3/api-docs) | Esquema JSON para importación en Postman/Insomnia |
| 🩺 **Health Check** | [smartchef-ai-api.onrender.com/actuator/health](https://smartchef-ai-api.onrender.com/actuator/health) | Estado del servicio y base de datos en Spring Actuator |

---

## 🌟 Características Principales

1. **Visión Multimodal con Google Gemini AI:**
   * Analiza imágenes (JPEG, PNG, WEBP) de refrigeradores o despensas.
   * Identifica ingredientes disponibles vs. ingredientes faltantes para cada receta.
   * Genera exactamente 3 recetas estructuradas (tiempo, calorías estimadas, nivel de dificultad y pasos ordenados).

2. **Arquitectura de Resiliencia y Fallback Automático:**
   * **Autodescubrimiento dinámico (*Model Discovery*):** Inspecciona en tiempo real los modelos disponibles en tu cuenta de Google Cloud / Google AI Studio mediante `ListModels`.
   * **Manejo inteligente de alta demanda (`429 / 503`):** Si un modelo sufre picos de demanda o cuota, el motor conmuta automáticamente en milisegundos al siguiente modelo de respaldo (`gemini-3.5-flash-lite`, `gemini-3.8-flash`, `gemini-2.5-flash`).
   * **Filtrado de bloques de pensamiento (*Thinking Tokens*):** Limpieza defensiva de razonamientos internos y delimitadores markdown para garantizar un JSON 100% estricto y parseable.

3. **Optimización de Imágenes en Memoria:**
   * Si la imagen subida supera 1 MB, `ImageProcessingService` la escala dinámicamente a máx. 1024 px manteniendo el ratio de aspecto mediante `BufferedImage`. Esto reduce la latencia de red con la IA de ~5 s a ~1.2 s.

4. **Seguridad y Persistencia:**
   * Autenticación sin estado con **Spring Security 6** y **JWT** (HMAC-SHA256, expiración 24 h).
   * Contraseñas cifradas con **BCrypt**.
   * Modo libre (anónimo) o autenticado (guarda historial de escaneos y recetas favoritas).
   * Conector de base de datos resiliente: PostgreSQL en producción (soporta `postgres://` de Render y `jdbc:postgresql://`) con fallback a H2 persistente en archivo o en memoria.

---

## 🛠️ Stack Tecnológico

| Componente | Tecnología | Versión |
|---|---|---|
| **Lenguaje** | Java | 21 (LTS) |
| **Framework** | Spring Boot | 3.3.4 |
| **Seguridad** | Spring Security + JJWT | 0.12.6 |
| **Base de Datos** | PostgreSQL / H2 Database | 42.7.4 |
| **ORM** | Hibernate / Spring Data JPA | 3.3.4 |
| **Inteligencia Artificial** | Google Gemini Vision REST API | v1beta / v1 |
| **Documentación** | Springdoc OpenAPI (Swagger UI) | 2.6.0 |
| **Contenedores** | Docker (Multi-stage build con Eclipse Temurin Alpine) | 21 JRE |
| **Pruebas** | JUnit 5 + Mockito + MockMvc + AssertJ | 74 pruebas (100% éxito) |

---

## 📋 Endpoints de la API

Prefijo base: `/api/v1`

### 1. Autenticación (`/api/v1/auth`)

| Método | Endpoint | Auth | Descripción |
|---|---|---|---|
| `POST` | `/auth/register` | Pública | Registro de usuario (username, email, contraseña). Devuelve JWT. |
| `POST` | `/auth/login` | Pública | Inicio de sesión con username o email. Devuelve JWT. |
| `GET` | `/auth/me` | JWT | Retorna el perfil del usuario autenticado. |

### 2. Recetas e IA (`/api/v1/recipes`)

| Método | Endpoint | Auth | Descripción |
|---|---|---|---|
| `POST` | `/recipes/generate-from-image` | Opcional | Recibe `multipart/form-data` con campo `image`. Si envías JWT, la sesión se vincula a tu historial. |
| `GET` | `/recipes/history` | JWT | Lista de sesiones de escaneo y recetas generadas por el usuario. |
| `POST` | `/recipes/{id}/favorite` | JWT | Alterna (añade o retira) una receta de la lista de favoritos. |
| `GET` | `/recipes/favorites` | JWT | Devuelve todas las recetas guardadas en favoritos. |
| `GET` | `/recipes/diagnostics` | Admin | Diagnóstico del estado de la conexión con la API de Google Gemini. |

---

## 💻 Ejecución Local

### 1. Clonar el repositorio

```bash
git clone https://github.com/MrOdar/smartchef-ai-api.git
cd smartchef-ai-api
```

### 2. Configurar variables de entorno

Copia `.env.example` o define `GEMINI_API_KEY`:

```bash
# Linux / macOS
export GEMINI_API_KEY="tu-api-key-de-google-ai-studio"

# Windows PowerShell
$env:GEMINI_API_KEY = "tu-api-key-de-google-ai-studio"
```

> Obtén tu clave gratuita en: [Google AI Studio](https://aistudio.google.com/apikey).

### 3. Compilar y ejecutar pruebas

Las pruebas se ejecutan **100% offline** (Gemini se simula con Mockito y la base de datos corre en memoria):

```bash
./mvnw clean test
```

### 4. Iniciar la aplicación

```bash
./mvnw spring-boot:run
```

Abre en tu navegador:
* **Aplicación Web:** `http://localhost:8080/`
* **Swagger UI:** `http://localhost:8080/swagger-ui.html`

---

## 🐳 Ejecución con Docker

```bash
docker build -t smartchef-ai-api .
docker run -p 8080:8080 -e GEMINI_API_KEY="tu-clave" smartchef-ai-api
```

---

## 🧪 Cobertura de Pruebas Automatizadas

El proyecto incluye **74 pruebas automatizadas** que cubren:
* **`GeminiVisionServiceTest`**: Respuestas limpias, markdown fences, textos con ruido, conmutación por error 404, conmutación por alta demanda (429/503), y aislamiento de credenciales.
* **`ImageProcessingServiceTest`**: Validación de formatos (JPEG, PNG, WEBP), rechazo de archivos inválidos o corruptos, y redimensionamiento exacto a 1024 px.
* **`JwtServiceTest`**: Emisión, validación, expiración de tokens y firmas inválidas.
* **`SmartChefApiIntegrationTest`**: Flujo end-to-end con MockMvc (registro, login, análisis de imagen anónimo y autenticado, historial por usuario, favoritos, códigos de error HTTP y compatibilidad UTF-8).
* **`DataSourceConfigTest`**: Validación de parsing dinámico de URLs PostgreSQL y H2.

```text
[INFO] Results:
[INFO] Tests run: 74, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

---

## 👤 Autor

**Odar Javier Alcocer Vásquez**  
Estudiante de Ingeniería de Sistemas de Información | Universidad Peruana de Ciencias Aplicadas (UPC), Lima, Perú.  
* **GitHub:** [@MrOdar](https://github.com/MrOdar)  
* **LinkedIn:** [linkedin.com/in/odar-alcocer](https://www.linkedin.com/in/odar-alcocer/)  
* **Email:** [odaralcv@gmail.com](mailto:odaralcv@gmail.com)

---

## 📄 Licencia

Este proyecto está bajo la Licencia MIT. Consulta el archivo `LICENSE` para más detalles.
