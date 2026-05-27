package com.keer.ticketmaster.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;

@Configuration
public class RestClientConfig {

    /**
     * RestClient with HTTP/2 + virtual thread executor for inter-pod communication.
     *
     * Why HTTP/2:
     *   - Multiplexing: thousands of concurrent requests over a single TCP connection
     *   - Eliminates HTTP/1.1 head-of-line blocking between API pods
     *   - h2c (cleartext HTTP/2) auto-negotiated with Tomcat (server.http2.enabled=true)
     *
     * Why virtual thread executor:
     *   - Non-blocking I/O on virtual threads instead of platform threads
     *   - Matches the project's virtual thread strategy
     */
    @Bean
    public RestClient restClient(ExecutorService virtualThreadExecutor) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .executor(virtualThreadExecutor)
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
