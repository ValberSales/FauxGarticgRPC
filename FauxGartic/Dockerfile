# 1. Compilação
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
# Baixa dependências para o cache local
RUN mvn dependency:go-offline
COPY src ./src
# Compila e (agora sim) copia as dependências para target/dependency
RUN mvn package -DskipTests

# 2. Execução
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copia apenas o que precisamos: o código compilado e as bibliotecas separadas
COPY --from=build /app/target/classes /app/classes
COPY --from=build /app/target/dependency /app/dependency

# Expõe a porta
EXPOSE 50051

# Roda apontando para as pastas certas
CMD ["java", "-cp", "/app/classes:/app/dependency/*", "org.fauxgartic.server.ServerApp"]