package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class WorkRemotelyService {

    private static final String RSS_URL =
            "https://weworkremotely.com/categories/remote-programming-jobs.rss";

    public List<Job> fetchJobs() {
        List<Job> jobs = new ArrayList<>();
        try {
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed = input.build(new XmlReader(new URL(RSS_URL)));

            for (SyndEntry entry : feed.getEntries()) {
                try {
                    String link = entry.getLink();
                    String rawTitle = entry.getTitle();

                    if (link == null || rawTitle == null) continue;

                    // Formato do WWR: "Company: Job Title"
                    String company = "Unknown";
                    String title = rawTitle;
                    if (rawTitle.contains(":")) {
                        String[] parts = rawTitle.split(":", 2);
                        company = parts[0].trim();
                        title = parts[1].trim();
                    }

                    LocalDateTime postedAt = entry.getPublishedDate() != null
                            ? entry.getPublishedDate().toInstant()
                                .atZone(ZoneId.systemDefault()).toLocalDateTime()
                            : LocalDateTime.now();

                    Job job = Job.builder()
                            .title(title)
                            .company(company)
                            .url(link)
                            .source("WWR")
                            .workplaceType("REMOTO")
                            .tags("remote,programming")
                            .postedAt(postedAt)
                            .fetchedAt(LocalDateTime.now())
                            .build();

                    jobs.add(job);
                } catch (Exception e) {
                    log.warn("Erro ao parsear entry do WWR: {}", e.getMessage());
                }
            }
            log.info("WWR: {} vagas buscadas", jobs.size());
        } catch (Exception e) {
            log.error("Erro ao buscar do WWR: {}", e.getMessage());
        }
        return jobs;
    }
}
