package com.keer.ticketmaster.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic sectionInitTopic() {
        return TopicBuilder.name(KafkaConstants.TOPIC_SECTION_INIT).partitions(20).replicas(1).build();
    }

    @Bean
    public NewTopic sectionStatusTopic() {
        return TopicBuilder.name(KafkaConstants.TOPIC_SECTION_STATUS).partitions(20).replicas(1).build();
    }

    @Bean
    public NewTopic reservationCommandsTopic() {
        return TopicBuilder.name(KafkaConstants.TOPIC_RESERVATION_COMMANDS).partitions(20).replicas(1).build();
    }

    @Bean
    public NewTopic reservationRequestsTopic() {
        return TopicBuilder.name(KafkaConstants.TOPIC_RESERVATION_REQUESTS).partitions(20).replicas(1).build();
    }

    @Bean
    public NewTopic reservationCompletedTopic() {
        return TopicBuilder.name(KafkaConstants.TOPIC_RESERVATION_COMPLETED).partitions(20).replicas(1).build();
    }
}
