package com.keer.ticketmaster.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AsyncConfig {

    /**
     * Shared virtual-thread executor for fire-and-forget I/O tasks
     * (Redis operations, HTTP forwarding) that should not block
     * Kafka Streams threads or caller threads.
     */
    @Bean(destroyMethod = "close")
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
