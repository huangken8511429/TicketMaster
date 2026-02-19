package com.keer.ticketmaster;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TicketMasterApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketMasterApplication.class, args);
    }

}
