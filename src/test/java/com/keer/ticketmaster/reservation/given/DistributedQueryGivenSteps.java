package com.keer.ticketmaster.reservation.given;

import com.keer.ticketmaster.ScenarioContext;
import com.keer.ticketmaster.avro.ReservationCompletedEvent;
import com.keer.ticketmaster.reservation.dto.ReservationResponse;
import com.keer.ticketmaster.reservation.service.InteractiveQueryService;
import io.cucumber.java.zh_tw.假如;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyQueryMetadata;
import org.apache.kafka.streams.state.HostInfo;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class DistributedQueryGivenSteps {

    @Autowired
    private ScenarioContext scenarioContext;

    @假如("^系統啟動了兩個服務實例，分別在 port (\\d+) 和 port (\\d+)$")
    public void 系統啟動了兩個服務實例(int port1, int port2) {
        // 建立 Instance 1 的 mock 依賴
        StreamsBuilderFactoryBean factory1 = mock(StreamsBuilderFactoryBean.class);
        KafkaStreams kafkaStreams1 = mock(KafkaStreams.class);
        RestClient restClient1 = mock(RestClient.class);

        when(factory1.getKafkaStreams()).thenReturn(kafkaStreams1);
        when(kafkaStreams1.state()).thenReturn(KafkaStreams.State.RUNNING);

        InteractiveQueryService instance1 = new InteractiveQueryService(factory1, restClient1);
        ReflectionTestUtils.setField(instance1, "applicationServer", "localhost:" + port1);

        // 建立 Instance 2 的 mock 依賴
        StreamsBuilderFactoryBean factory2 = mock(StreamsBuilderFactoryBean.class);
        KafkaStreams kafkaStreams2 = mock(KafkaStreams.class);
        RestClient restClient2 = mock(RestClient.class);

        when(factory2.getKafkaStreams()).thenReturn(kafkaStreams2);
        when(kafkaStreams2.state()).thenReturn(KafkaStreams.State.RUNNING);

        InteractiveQueryService instance2 = new InteractiveQueryService(factory2, restClient2);
        ReflectionTestUtils.setField(instance2, "applicationServer", "localhost:" + port2);

        // 存入 ScenarioContext 供後續步驟使用
        scenarioContext.set("instance1", instance1);
        scenarioContext.set("instance2", instance2);
        scenarioContext.set("kafkaStreams1", kafkaStreams1);
        scenarioContext.set("kafkaStreams2", kafkaStreams2);
        scenarioContext.set("restClient2", restClient2);
        scenarioContext.set("port1", port1);
    }

    @SuppressWarnings("unchecked")
    @假如("^一筆預訂「(.+)」已寫入 reservation-completed topic，使用者為「(.+)」，狀態為「(.+)」，座位為「(.+)」$")
    public void 一筆預訂已寫入topic(String reservationId, String userId, String status, String seatsStr) {
        List<String> seats = Arrays.asList(seatsStr.split(","));
        int port1 = (int) scenarioContext.get("port1");
        long timestamp = Instant.now().toEpochMilli();

        KafkaStreams kafkaStreams1 = (KafkaStreams) scenarioContext.get("kafkaStreams1");
        KafkaStreams kafkaStreams2 = (KafkaStreams) scenarioContext.get("kafkaStreams2");
        RestClient restClient2 = (RestClient) scenarioContext.get("restClient2");

        // 預訂的 partition 歸屬於 instance1 — 兩個實例的 metadata 都指向 instance1
        KeyQueryMetadata metadata = new KeyQueryMetadata(
                new HostInfo("localhost", port1), Set.of(), 0);

        when(kafkaStreams1.queryMetadataForKey(anyString(), eq(reservationId), any(Serializer.class)))
                .thenReturn(metadata);
        when(kafkaStreams2.queryMetadataForKey(anyString(), eq(reservationId), any(Serializer.class)))
                .thenReturn(metadata);

        // Instance 1 的本地 store 中有這筆預訂
        ReservationCompletedEvent event = ReservationCompletedEvent.newBuilder()
                .setReservationId(reservationId)
                .setEventId(1L)
                .setUserId(userId)
                .setStatus(status)
                .setSection("A")
                .setSeatCount(seats.size())
                .setAllocatedSeats(seats)
                .setTimestamp(timestamp)
                .build();

        ReadOnlyKeyValueStore<String, ReservationCompletedEvent> store = mock(ReadOnlyKeyValueStore.class);
        when(kafkaStreams1.store(any())).thenReturn(store);
        when(store.get(reservationId)).thenReturn(event);

        // Instance 2 透過 RestClient 遠端查詢 instance1
        ReservationResponse remoteResponse = ReservationResponse.builder()
                .reservationId(reservationId)
                .eventId(1L)
                .userId(userId)
                .status(status)
                .section("A")
                .seatCount(seats.size())
                .allocatedSeats(seats)
                .createdAt(Instant.ofEpochMilli(timestamp))
                .build();

        var uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        var headersSpec = mock(RestClient.RequestHeadersSpec.class);
        var responseSpec = mock(RestClient.ResponseSpec.class);

        doReturn(uriSpec).when(restClient2).get();
        doReturn(headersSpec).when(uriSpec).uri(anyString());
        doReturn(responseSpec).when(headersSpec).retrieve();
        when(responseSpec.body(ReservationResponse.class)).thenReturn(remoteResponse);

        scenarioContext.set("uriSpec", uriSpec);
        scenarioContext.set("reservationId", reservationId);
    }
}
