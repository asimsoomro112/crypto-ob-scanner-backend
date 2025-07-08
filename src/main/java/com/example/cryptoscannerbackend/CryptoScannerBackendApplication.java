package com.example.cryptoscannerbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableJpaRepositories(basePackages = "com.example.cryptoscannerbackend.repository")
public class CryptoScannerBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(CryptoScannerBackendApplication.class, args);
    }

}
