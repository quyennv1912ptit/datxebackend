package com.nhom15.datxebackend.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.GeoOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations

@Configuration
class RedisConfig {

    /** GEOADD / GEOSEARCH cho key available_drivers */
    @Bean
    fun geoOperations(template: StringRedisTemplate): GeoOperations<String, String> =
        template.opsForGeo()

    /** SET key value NX PX ttl: khóa offer/ride, heartbeat tài xế */
    @Bean
    fun valueOperations(template: StringRedisTemplate): ValueOperations<String, String> =
        template.opsForValue()
}