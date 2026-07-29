package com.adaiadai.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AdaiCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdaiCoreApplication.class, args);
    }
}
