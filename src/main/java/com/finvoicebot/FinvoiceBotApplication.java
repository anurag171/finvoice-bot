package com.finvoicebot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;


@SpringBootApplication
@EntityScan
public class FinvoiceBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinvoiceBotApplication.class, args);
    }
}
