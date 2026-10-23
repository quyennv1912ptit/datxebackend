package com.nhom15.datxebackend.common.event

interface EventPublisher {
    fun publish(event: DomainEvent)
}