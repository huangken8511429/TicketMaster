package com.keer.ticketmaster.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Value("${ticketmaster.kafka.partitions:32}")
    private int partitions;

    @Value("${ticketmaster.kafka.replicas:1}")
    private int replicas;

    @Bean
    public NewTopic sectionInitTopic() {
        return TopicBuilder.name(Topic.SECTION_INIT).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    public NewTopic sectionStatusTopic() {
        return TopicBuilder.name(Topic.SECTION_STATUS).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    public NewTopic bookingCommandsTopic() {
        return TopicBuilder.name(Topic.BOOKING_COMMANDS).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    public NewTopic bookingCompletedTopic() {
        return TopicBuilder.name(Topic.BOOKING_COMPLETED).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    public NewTopic seatAllocationRequestsTopic() {
        return TopicBuilder.name(Topic.SEAT_ALLOCATION_REQUESTS).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    public NewTopic seatAllocationResultsTopic() {
        return TopicBuilder.name(Topic.SEAT_ALLOCATION_RESULTS).partitions(partitions).replicas(replicas).build();
    }
}
