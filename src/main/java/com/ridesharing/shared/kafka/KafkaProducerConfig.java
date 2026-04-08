package com.ridesharing.shared.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Configures how our application SENDS messages to Kafka topics.
 *
 * Flow: Java Object → JsonSerializer → Kafka topic (stored as JSON bytes)
 *
 * Key decisions:
 *   - Key serializer: StringSerializer (ride ID or driver ID as the key)
 *     Same key = same partition = guaranteed ordering for that ride/driver
 *   - Value serializer: JsonSerializer (event objects like RideRequestedEvent)
 *     JSON is human-readable and easy to debug in Kafka console
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * Defines the producer settings: where Kafka is, how to serialize data.
     *
     * @return ProducerFactory that KafkaTemplate uses internally
     */
    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();

        // Address of Kafka broker(s). In dev: localhost:9092. In prod: AWS MSK endpoint.
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        // Keys are Strings (e.g., rideId "abc-123"). Same key goes to same partition.
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // Values are Java objects serialized to JSON bytes.
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        return new DefaultKafkaProducerFactory<>(configProps);
    }

    /**
     * KafkaTemplate is the main class we use to SEND messages.
     * Usage: kafkaTemplate.send(KafkaTopics.RIDE_REQUESTED, rideId, event);
     *
     * @return KafkaTemplate ready to publish events
     */
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
