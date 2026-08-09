package br.com.jobradar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableAsync ativa o processamento de @Async — usado pelo fetch inicial
// (JobAggregatorService), que agora roda em background depois que o Tomcat
// já está escutando, em vez de bloquear a inicialização (ver comentário lá).
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class JobRadarApplication {
    public static void main(String[] args) {
        SpringApplication.run(JobRadarApplication.class, args);
    }
}
