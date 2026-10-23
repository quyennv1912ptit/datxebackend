package com.nhom15.datxebackend.common.event

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
class SpringEventPublisher(
    private val delegate: ApplicationEventPublisher
) : EventPublisher {

    override fun publish(event: DomainEvent) {
        delegate.publishEvent(event)
    }
}