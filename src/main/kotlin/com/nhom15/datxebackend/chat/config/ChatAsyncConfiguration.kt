package com.nhom15.datxebackend.chat.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
@EnableAsync
class ChatAsyncConfiguration {
    @Bean("chatTaskExecutor")
    fun chatTaskExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2
        maxPoolSize = 8
        queueCapacity = 500
        setThreadNamePrefix("chat-push-")
        setWaitForTasksToCompleteOnShutdown(true)
    }
}
