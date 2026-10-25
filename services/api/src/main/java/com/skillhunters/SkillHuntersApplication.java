package com.skillhunters;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class SkillHuntersApplication {
    public static void main(String[] args) {
        SpringApplication.run(SkillHuntersApplication.class, args);
    }
}
