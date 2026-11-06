package com.nhom15.datxebackend.notification.service

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.ThreadPoolExecutor

@Configuration
class NotificationExecutorConfig {
    @Bean("notificationExecutor")
    fun notificationExecutor() = ThreadPoolTaskExecutor().apply {
        corePoolSize = 8
        maxPoolSize = 8
        queueCapacity = 128
        setThreadNamePrefix("notification-")
        setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
    }
}
