package br.com.jobradar.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Fase 5.4 — backup automático do Postgres via {@code pg_dump}, rodando
 * dentro do próprio container backend (a imagem ganhou o pacote
 * {@code postgresql16-client} no Dockerfile só pra isso — mesma versão
 * major do servidor, evita incompatibilidade de formato de dump).
 *
 * <p>Antes disso a única forma de backup era manual (ver comentário em
 * {@code EmbeddingColumnMigrationService}/{@code JobEmbeddingBinaryMigrationService}
 * sobre tirar um {@code pg_dump} manual antes de migração arriscada) — essa
 * classe automatiza o mesmo comando numa rotina diária, sem depender de
 * alguém lembrar de rodar antes de mexer em algo.</p>
 *
 * <p>Formato custom ({@code -F c}), não SQL puro — comprime sozinho e
 * permite restore seletivo (só uma tabela, por exemplo) via
 * {@code pg_restore}, sem precisar editar um arquivo .sql gigante na mão.</p>
 */
@Service
@Slf4j
public class BackupService {

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Value("${backup.dir:/app/backups}")
    private String backupDir;

    // Backup diário: espaço em disco é finito, e um app pessoal não precisa
    // de retenção de meses — 14 dias cobre "percebi um problema na semana
    // passada, quero comparar com antes" sem crescer sem limite.
    @Value("${backup.retention-days:14}")
    private int retentionDays;

    private static final DateTimeFormatter STAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");
    // jdbc:postgresql://host:port/database — formato único usado neste
    // projeto (sem query params), então um regex simples basta; não precisa
    // de um parser de JDBC URL de verdade pra um caso só.
    private static final Pattern JDBC_URL = Pattern.compile("jdbc:postgresql://([^:/]+):(\\d+)/([^?]+)");

    public record BackupResult(boolean ok, String arquivo, long tamanhoBytes, String erro) {}

    /**
     * POST /api/jobs/admin/backup (via JobAdminController) e a rotina diária
     * chamam este método — mesmo caminho de código pros dois, pra nunca ter
     * "o manual funciona mas o automático não" ou vice-versa.
     * Cron: 4h da manhã, horário de menor uso do app pessoal.
     */
    @Scheduled(cron = "0 0 4 * * *", zone = "America/Sao_Paulo")
    public void backupAutomatico() {
        BackupResult resultado = executarBackup();
        if (resultado.ok()) {
            log.info("=== Backup automático concluído: {} ({} KB) ===", resultado.arquivo(), resultado.tamanhoBytes() / 1024);
            limparBackupsAntigos();
        } else {
            log.error("=== Backup automático FALHOU: {} ===", resultado.erro());
        }
    }

    public BackupResult executarBackup() {
        Matcher m = JDBC_URL.matcher(datasourceUrl);
        if (!m.matches()) {
            return new BackupResult(false, null, 0, "spring.datasource.url em formato inesperado: " + datasourceUrl);
        }
        String host = m.group(1);
        String port = m.group(2);
        String database = m.group(3);

        Path dir = Path.of(backupDir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            return new BackupResult(false, null, 0, "Não consegui criar o diretório de backup: " + e.getMessage());
        }

        String nomeArquivo = "jobradar-" + LocalDateTime.now().format(STAMP_FMT) + ".dump";
        Path destino = dir.resolve(nomeArquivo);

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "pg_dump",
                    "-h", host, "-p", port, "-U", username,
                    "-F", "c", // formato custom — comprimido, restore seletivo com pg_restore
                    "-f", destino.toString(),
                    database
            );
            pb.environment().put("PGPASSWORD", password);
            pb.redirectErrorStream(true);
            Process processo = pb.start();

            String saida;
            try (var reader = processo.inputReader()) {
                saida = reader.lines().reduce("", (a, b) -> a + "\n" + b);
            }
            boolean terminou = processo.waitFor(5, TimeUnit.MINUTES);
            if (!terminou) {
                processo.destroyForcibly();
                return new BackupResult(false, null, 0, "pg_dump não terminou em 5 minutos — abortado.");
            }
            if (processo.exitValue() != 0) {
                Files.deleteIfExists(destino); // dump parcial/inválido não fica pra trás
                return new BackupResult(false, null, 0, "pg_dump saiu com código " + processo.exitValue() + ": " + saida);
            }

            long tamanho = Files.size(destino);
            return new BackupResult(true, nomeArquivo, tamanho, null);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new BackupResult(false, null, 0, "Erro ao executar pg_dump: " + e.getMessage());
        }
    }

    // Roda só depois de um backup NOVO ter sido criado com sucesso — nunca
    // no boot nem antes de confirmar que o dia de hoje já tem um backup
    // válido, pra nunca ficar com ZERO backups por causa de uma limpeza mal
    // sincronizada com uma falha de escrita.
    private void limparBackupsAntigos() {
        Path dir = Path.of(backupDir);
        Instant limite = Instant.now().minus(retentionDays, java.time.temporal.ChronoUnit.DAYS);
        try (Stream<Path> arquivos = Files.list(dir)) {
            arquivos
                    .filter(p -> p.getFileName().toString().startsWith("jobradar-") && p.getFileName().toString().endsWith(".dump"))
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toInstant().isBefore(limite);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                            log.info("Backup antigo removido: {}", p.getFileName());
                        } catch (IOException e) {
                            log.warn("Não consegui remover backup antigo {}: {}", p.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Não consegui listar diretório de backups pra limpeza: {}", e.getMessage());
        }
    }

    public record BackupInfo(String arquivo, long tamanhoBytes, long modificadoEm) {}

    /** GET /api/jobs/admin/backups — lista o que já foi feito, mais recente primeiro. */
    public List<BackupInfo> listarBackups() {
        Path dir = Path.of(backupDir);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> arquivos = Files.list(dir)) {
            return arquivos
                    .filter(p -> p.getFileName().toString().startsWith("jobradar-") && p.getFileName().toString().endsWith(".dump"))
                    .map(p -> {
                        try {
                            return new BackupInfo(p.getFileName().toString(), Files.size(p), Files.getLastModifiedTime(p).toMillis());
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .sorted((a, b) -> Long.compare(b.modificadoEm(), a.modificadoEm()))
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            log.warn("Não consegui listar backups: {}", e.getMessage());
            return List.of();
        }
    }
}
