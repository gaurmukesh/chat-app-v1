package com.mg.chat_app.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mg.chat_app.security.RateLimitFilter;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;

@Configuration
public class RateLimitConfig {

    @Bean(destroyMethod = "shutdown")
    public RedisClient lettuceRedisClient(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port:6379}") int port) {
        return RedisClient.create(RedisURI.builder().withHost(host).withPort(port).build());
    }

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, byte[]> lettuceConnection(RedisClient lettuceRedisClient) {
        return lettuceRedisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    @Bean
    public ProxyManager<String> bucketProxyManager(StatefulRedisConnection<String, byte[]> lettuceConnection) {
        return Bucket4jLettuce.casBasedBuilder(lettuceConnection)
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                        Duration.ofMinutes(15)))
                .build();
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(ProxyManager<String> bucketProxyManager) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RateLimitFilter(bucketProxyManager));
        registration.addUrlPatterns("/api/auth/login", "/api/auth/register");
        registration.setOrder(1);
        return registration;
    }
}
