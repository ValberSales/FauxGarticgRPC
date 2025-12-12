# 1. Compilação
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
# Baixa dependências (Cache)
RUN mvn dependency:go-offline
COPY src ./src
# Compila tudo
RUN mvn package -DskipTests

# 2. Execução (Apenas o Servidor)
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copia as bibliotecas baixadas e o código compilado
COPY --from=build /root/.m2 /root/.m2
COPY --from=build /app/target /app/target

# Expõe a porta gRPC
EXPOSE 50051

# Roda a classe ServerApp
CMD ["java", "-cp", "target/classes:target/dependency/*", "org.fauxgartic.server.ServerApp"]