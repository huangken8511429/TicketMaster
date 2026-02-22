package com.keer.ticketmaster;

import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.test.context.EmbeddedKafka;

@CucumberContextConfiguration
@SpringBootTest
@Import(TestConfig.class)
@EmbeddedKafka(
        partitions = 1,
        topics = {
                "section-init",
                "section-status",
                "reservation-commands",
                "reservation-requests",
                "reservation-completed"
        }
)
public class CucumberSpringConfiguration {
}
