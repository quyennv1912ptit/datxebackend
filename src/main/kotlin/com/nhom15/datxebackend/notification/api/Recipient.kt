package com.nhom15.datxebackend.notification.api

import com.nhom15.datxebackend.common.enums.Role

sealed interface Recipient {
    val id: Long
    val key: String
    val role: Role

    data class Customer(override val id: Long) : Recipient {
        override val role: Role = Role.CUSTOMER
        override val key: String = "CUSTOMER:$id"
    }

    data class Driver(override val id: Long) : Recipient {
        override val role: Role = Role.DRIVER
        override val key: String = "DRIVER:$id"
    }
}
