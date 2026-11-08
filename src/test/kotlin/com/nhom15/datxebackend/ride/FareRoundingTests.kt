package com.nhom15.datxebackend.ride

import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.port.CustomerVehicleSnapshot
import com.nhom15.datxebackend.pricing.model.RideQuote
import com.nhom15.datxebackend.ride.dto.request.CreateRideRequest
import com.nhom15.datxebackend.ride.service.RideFactory
import com.nhom15.datxebackend.ride.service.RideResponseMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class FareRoundingTests {
    private val request = CreateRideRequest(
        vehicleId = 10,
        pickupAddress = "Điểm đón",
        pickupLat = 21.01,
        pickupLng = 105.80,
        dropoffAddress = "Điểm trả",
        dropoffLat = 21.03,
        dropoffLng = 105.82,
    )
    private val vehicle = CustomerVehicleSnapshot(10, 100, Transmission.AUTO, VehicleType.CAR, "29A-99999")

    private fun quote(fare: Long) = RideQuote(1, BigDecimal("3.0"), 10, 20_000, 11_250, 0, 0, 0, fare)

    @Test fun `estimate payable is rounded after voucher`() {
        assertEquals(31_000L, RideResponseMapper.estimate(quote(31_250)).payableFare)
        assertEquals(32_000L, RideResponseMapper.estimate(quote(31_500)).payableFare)
        assertEquals(28_000L, RideResponseMapper.estimate(quote(31_250), discountAmount = 3_125).payableFare)
    }

    @Test fun `create response rounds payable but keeps the original estimate`() {
        val ride = RideFactory.create(request, 100, vehicle, quote(31_250), 3_125)

        val response = RideResponseMapper.create(ride)

        assertEquals(28_000L, response.payableFare)
        assertEquals(31_250L, response.estimatedFare)
        assertEquals(3_125L, response.discountAmount)
    }
}
