package com.seatvault.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;
import java.util.Map;

/**
 * Redis-backed cache configuration.
 *
 * <h3>What we cache and why</h3>
 * Event listings and per-event seat-availability are read far more often than they
 * change (browsing >> booking), so they're the high-value cache targets. We use
 * short TTLs as a safety net on top of explicit invalidation, so even if an
 * eviction is ever missed the data self-heals quickly.
 *
 * <h3>Cache names &amp; TTLs</h3>
 * <ul>
 *   <li>{@code events}        — single event detail, 60s.</li>
 *   <li>{@code eventSearch}   — paginated search results, 30s (shorter: many keys,
 *       and availability drifts as people book).</li>
 *   <li>{@code eventSeats}    — a single event's seat list, 20s.</li>
 * </ul>
 *
 * <p>Values are stored as JSON (human-inspectable in redis-cli, language-agnostic).
 * Default-typing is enabled so polymorphic DTOs (records, {@code PagedResponse})
 * round-trip with their concrete types.
 */
@Configuration
public class CacheConfig {

    public static final String EVENTS = "events";
    public static final String EVENT_SEARCH = "eventSearch";
    public static final String EVENT_SEATS = "eventSeats";

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // Jackson configured to (de)serialize records, java.time, and to embed type
        // info so Redis JSON deserializes back into the right DTO classes.
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);

        // Default typing embeds a @class id so cached JSON round-trips back into the
        // right DTO (PagedResponse, EventResponse, …). The PolymorphicTypeValidator
        // gates which type ids are allowed on READ.
        //
        // IMPORTANT: do NOT use `allowIfBaseType(Object.class)` here. Default typing
        // deserializes against base type Object, and that rule fails to allow our own
        // types — so reading a cached value throws "PolymorphicTypeValidator denied
        // resolution ... com.seatvault.dto.PagedResponse" and the request 500s on
        // every cache HIT (writes succeed, so the FIRST call works and the next fails).
        // Instead, explicitly allow our package plus the JDK types we actually cache
        // (lists, java.time, boxed primitives). This is safe because the cache only
        // ever holds values WE wrote, never untrusted input.
        BasicPolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.seatvault.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.time.")
                .allowIfSubType("java.lang.")
                .build();
        // Use EVERYTHING, not NON_FINAL: our cached DTOs (PagedResponse, EventResponse,
        // SeatResponse) are Java RECORDS, which are implicitly final. With NON_FINAL,
        // Jackson skips the @class type id for final types on WRITE, but on READ the
        // value's declared type is Object and needs that id — so reads fail with
        // "missing type id property '@class'" and every cache HIT 500s (the write/miss
        // works, the next hit breaks). EVERYTHING emits @class for final types too.
        mapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.EVERYTHING, JsonTypeInfo.As.PROPERTY);

        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(mapper);

        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));

        Map<String, RedisCacheConfiguration> perCache = Map.of(
                EVENTS, base.entryTtl(Duration.ofSeconds(60)),
                EVENT_SEARCH, base.entryTtl(Duration.ofSeconds(30)),
                EVENT_SEATS, base.entryTtl(Duration.ofSeconds(20))
        );

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base.entryTtl(Duration.ofSeconds(60)))
                .withInitialCacheConfigurations(perCache)
                .build();
    }
}
