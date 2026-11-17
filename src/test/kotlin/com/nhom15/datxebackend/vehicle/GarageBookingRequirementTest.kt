package com.nhom15.datxebackend.vehicle

import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.vehicle.catalog.EngineType
import com.nhom15.datxebackend.vehicle.dto.CustomerVehicleSpecifications
import com.nhom15.datxebackend.vehicle.repository.bookingRequirement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class GarageBookingRequirementTest {
    @Test
    fun `groups use declared specifications at policy boundaries`() {
        fun moto(cc: Int) = bookingRequirement(VehicleType.MOTORCYCLE, Transmission.AUTO, cc, null)
        assertEquals(null to "OUTSIDE_MVP", moto(49))
        assertEquals("moto-50-125" to "CUSTOMER_DECLARED", moto(125))
        assertEquals("moto-126-174" to "CUSTOMER_DECLARED", moto(126))
        assertEquals("moto-175-plus" to "CUSTOMER_DECLARED", moto(175))
        fun electric(kw: Double) = bookingRequirement(VehicleType.MOTORCYCLE, Transmission.AUTO, null,
            CustomerVehicleSpecifications(engineType = EngineType.ELECTRIC, electricMotorKw = kw))
        assertEquals("moto-electric-4-11", electric(11.0).first)
        assertEquals("moto-electric-over-11-under-14", electric(11.01).first)
        assertEquals("moto-electric-14-plus", electric(14.0).first)
    }

    @Test
    fun `unknown seating or trailer stays unresolved and known unsupported vehicle is excluded`() {
        fun car(seats: Int?, trailer: Int?) = bookingRequirement(VehicleType.CAR, Transmission.MANUAL, null,
            CustomerVehicleSpecifications(passengerSeats = seats, trailerMassKg = trailer))
        assertEquals(null to "NEEDS_DETAILS", car(4, null))
        assertEquals("car-manual" to "CUSTOMER_DECLARED", car(8, 0))
        assertEquals(null to "OUTSIDE_MVP", car(9, 0))
        assertEquals(null to "OUTSIDE_MVP", car(4, 200))
    }
}
