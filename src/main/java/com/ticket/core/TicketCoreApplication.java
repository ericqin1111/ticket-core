package com.ticket.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TicketCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketCoreApplication.class, args);
    }
}
