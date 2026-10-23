package com.nhom15.datxebackend.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

@Configuration
@EnableScheduling
class SchedulingConfig {

    @Bean(name = ["taskScheduler"])
    fun taskScheduler(): ThreadPoolTaskScheduler = ThreadPoolTaskScheduler().apply {
        setPoolSize(4)
        setThreadNamePrefix("app-sched-")
        setRemoveOnCancelPolicy(true)
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(20)
    }
}
