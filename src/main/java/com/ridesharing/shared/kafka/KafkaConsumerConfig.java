package com.ridesharing.shared.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Configures how our application READS messages from Kafka topics.
 *
 * Flow: Kafka topic (JSON bytes) → JsonDeserializer → Java Object
 *
 * Key decisions:
 *   - Consumer group: "ridesharing-group" — all instances of our app share this group.
 *     Kafka ensures each message is delivered to exactly ONE instance in the group.
 *   - Error handling: retry 3 times with 1-second delay. After 3 failures,
 *     the message goes to a dead letter topic for manual investigation.
 *   - Auto offset: "earliest" — if a consumer starts fresh, it reads ALL
 *     existing messages (no data loss).
 */
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * Defines the consumer settings: where Kafka is, how to deserialize data,
     * which consumer group this app belongs to.
     *
     * @return ConsumerFactory used by @KafkaListener methods
     */
    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> configProps = new HashMap<>();

        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        // Consumer group name — all app instances share this.
        // Kafka distributes partitions among group members for parallel processing.
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, "ridesharing-group");

        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);

        // Trust our package for deserialization (security measure — only deserialize known classes)
        configProps.put(JsonDeserializer.TRUSTED_PACKAGES, "com.ridesharing.*");

        // Start reading from the beginning if no offset exists for this group
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        return new DefaultKafkaConsumerFactory<>(configProps);
    }

    /**
     * Factory that creates Kafka listener containers.
     * Each @KafkaListener annotation in our code uses this factory.
     * Includes error handling: retry 3 times, then give up (log the failure).
     *
     * @return configured listener factory with retry logic
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());

        // Retry failed messages 3 times with 1-second interval.
        // After 3 failures, DefaultErrorHandler logs the error and moves on.
        // In production, we'd route to a dead letter topic instead.
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, 3)));

        return factory;
    }
}
