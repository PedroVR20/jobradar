package br.com.jobradar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JobRadarApplication {
    public static void main(String[] args) {
        SpringApplication.run(JobRadarApplication.class, args);
    }
}
