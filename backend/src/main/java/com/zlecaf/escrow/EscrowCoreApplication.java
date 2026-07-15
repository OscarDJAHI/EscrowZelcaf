package com.zlecaf.escrow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class EscrowCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(EscrowCoreApplication.class, args);
    }
}
