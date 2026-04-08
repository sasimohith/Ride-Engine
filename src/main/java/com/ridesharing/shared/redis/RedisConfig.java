package com.ridesharing.shared.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Configures Redis connection and RedisTemplate for the entire application.
 *
 * Redis is used for 3 purposes:
 *   1. GEO operations   — store driver locations, query "drivers within 3 km"
 *   2. Caching           — fare rules, driver profiles (avoid hitting PostgreSQL)
 *   3. Temporary data    — ride requests awaiting driver acceptance (TTL = 60s)
 *
 * RedisConnectionFactory is auto-configured by Spring Boot from application-dev.yml
 * (host, port). We just customize HOW data is serialized when stored.
 *
 * Serialization choice:
 *   Key   → StringRedisSerializer  (keys are always readable strings like "driver:location:123")
 *   Value → GenericJackson2Json     (values are Java objects converted to JSON)
 */
@Configuration
public class RedisConfig {

    /**
     * Creates a RedisTemplate that all modules will use to interact with Redis.
     *
     * @param connectionFactory auto-injected by Spring from redis config in application.yml
     * @return configured RedisTemplate ready for String keys and JSON values
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Keys are stored as plain strings: "driver:location:42", "ride:request:789"
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Values are stored as JSON: {"latitude": 12.97, "longitude": 77.59}
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
