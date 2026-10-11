package com.skillhunters.documents;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="app.analysis.enabled",havingValue="true")
public class AnalysisScheduler {
    private final AnalysisService service;
    public AnalysisScheduler(AnalysisService service) { this.service=service; }
    @Scheduled(fixedDelayString="${app.analysis.poll-ms:5000}")
    public void poll() { service.runOnce(); }
}
