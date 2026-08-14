# Stage 1: Build com Maven
FROM maven:3.9-amazoncorretto-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

# Stage 2: Runtime leve
FROM amazoncorretto:21-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
# -Xmx explícito (real fix pontual, não definitivo — ver comentário em
# JobRepository/JobController sobre o próximo passo de verdade): sem isso
# o JVM heurística pra 25% da memória DETECTADA DO HOST (não do limite do
# container), e listagens sem paginação (ex: GET /api/jobs?onlyNew=true)
# carregam toda vaga NOVA inteira — incluindo o campo embedding (~alguns
# KB cada, populado em massa pelo backfill da Fase 1.1) — de uma vez.
# Com o catálogo tendo crescido bastante nesta sessão (Greenhouse +
# backfills), isso estourou o heap default e travou o backend
# (OutOfMemoryError sem crash-loop, container ficava "Up" mas travado).
# 2g é folga real dado que o host/container têm memória de sobra — não
# resolve o problema de fundo (que é a query não paginada carregando
# coluna que a listagem nem usa), só evita o crash até isso ser corrigido
# de verdade.
ENTRYPOINT ["java", "-Xmx2g", "-Xms512m", "-jar", "app.jar"]
