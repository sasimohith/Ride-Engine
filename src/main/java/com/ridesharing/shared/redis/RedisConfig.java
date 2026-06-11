package com.ridesharing.shared.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        mapper.activateDefaultTyping(
                mapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL);

        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(mapper);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
