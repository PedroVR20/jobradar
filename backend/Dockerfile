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
# Fase 5.4 — pg_dump pro backup automático (ver BackupService). Versão 16
# de propósito (postgresql16-client), pra bater com o postgres:16-alpine do
# docker-compose.yml — dump gerado por uma versão de cliente mais nova que
# o servidor às vezes falha ou usa formato que a mais antiga não entende.
RUN apk add --no-cache postgresql16-client
EXPOSE 8080
# Fase 6.6 — -Xmx2g (Fase 1) foi tampão de emergência pro OOM real de
# então: o campo embedding (~39 mil caracteres/vaga) vivia dentro de Job e
# QUALQUER listagem sem paginação (ex: GET /api/jobs?onlyNew=true, ou o
# findAll() de /export) arrastava ele junto pra cada linha. A causa raiz
# foi corrigida de verdade desde então — embedding saiu do Job pra tabela
# própria (Fase 6.1) e virou bytea compacto (Fase 6.5), e a listagem
# principal ganhou paginação de verdade (Fase 6.3) — então 2g parou de
# ser necessário, só reservava memória que os outros containers (postgres,
# frontend) também disputam no mesmo host/VM.
#
# Medido antes de reverter (não assumido): jcmd GC.heap_info depois de
# bater repetidamente em /api/jobs/export?format=json (o pior caso que
# sobrou sem paginação, ~5900 vagas de uma vez) — heap usado ficou em
# ~350 MB, bem abaixo mesmo do heap committed de ~860 MB. 1g dá quase 3x
# de folga sobre o pico medido, o suficiente pro catálogo crescer bastante
# antes de precisar revisar de novo.
ENTRYPOINT ["java", "-Xmx1g", "-Xms512m", "-jar", "app.jar"]
