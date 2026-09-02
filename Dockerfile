# ============ Stage 1: build ============
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Capa de dependencias: se reutiliza mientras el pom no cambie.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests clean package

# ============ Stage 2: runtime ============
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Usuario sin privilegios
RUN addgroup -S smartchef && adduser -S smartchef -G smartchef

COPY --from=build /build/target/*.jar app.jar

RUN chown -R smartchef:smartchef /app
USER smartchef

# Render inyecta PORT en tiempo de ejecucion.
ENV PORT=8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8 -Djava.awt.headless=true"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
