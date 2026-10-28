package com.nhom15.datxebackend.pricing

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.exception.GlobalExceptionHandler
import com.nhom15.datxebackend.pricing.entity.PricingRule
import com.nhom15.datxebackend.pricing.model.RideQuoteInput
import com.nhom15.datxebackend.pricing.repository.PricingRuleRepository
import com.nhom15.datxebackend.pricing.service.PricingRideQuoteService
import com.nhom15.datxebackend.routing.api.DrivingRouteProvider
import com.nhom15.datxebackend.routing.model.DrivingRoute
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus

class PricingRideQuoteTests {
    private val repository = Mockito.mock(PricingRuleRepository::class.java)
    private val routes = Mockito.mock(DrivingRouteProvider::class.java)
    private val service = PricingRideQuoteService(repository, routes)
    private fun input(type: VehicleType) = RideQuoteInput(GeoPoint(10.77, 106.7), GeoPoint(10.8, 106.75), Instant.parse("2026-10-28T04:00:00Z"), type)

    @Test
    fun `missing active pricing returns a structured 404 without requesting a route`() {
        val error = assertThrows(AppException::class.java) { service.quote(input(VehicleType.CAR)) }
        assertEquals(ErrorCode.PRICING_RULE_NOT_FOUND, error.errorCode)
        val response = GlobalExceptionHandler().handleApp(error)
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals(false, response.body?.success)
        assertEquals("PRICING_RULE_NOT_FOUND", response.body?.error?.code)
        assertFalse(response.body?.error?.message.isNullOrBlank())
        Mockito.verifyNoInteractions(routes)
    }

    @Test
    fun `quotes use repository pricing and respect minimum fare for both vehicle types`() {
        for ((type, base, perKm, minimum) in listOf(
            listOf(VehicleType.CAR, 20000L, 10000L, 30000L),
            listOf(VehicleType.MOTORCYCLE, 10000L, 5000L, 15000L),
        )) {
            val vehicleType = type as VehicleType
            Mockito.`when`(repository.findFirstByVehicleTypeAndActiveTrueOrderByCreatedAtDesc(vehicleType))
                .thenReturn(PricingRule(baseFare = base as Long, perKm = perKm as Long, perMinuteWaiting = 0, minFare = minimum as Long, active = true, vehicleType = vehicleType))
            val request = input(vehicleType)
            Mockito.`when`(routes.findRoute(request.pickup, request.dropoff, vehicleType)).thenReturn(DrivingRoute(500.0, 60.0))
            val quote = service.quote(request)
            assertEquals(minimum, quote.estimatedFare)
            assertEquals(base, quote.baseFare)
            assertEquals(perKm / 2, quote.distanceFare)
        }
    }
    @Test
    fun `pricing consumes the saved route without calling routing again`() {
        Mockito.`when`(repository.findFirstByVehicleTypeAndActiveTrueOrderByCreatedAtDesc(VehicleType.CAR))
            .thenReturn(PricingRule(baseFare = 20000, perKm = 10000, perMinuteWaiting = 0, minFare = 30000, active = true, vehicleType = VehicleType.CAR))
        val route = DrivingRoute(2500.0, 300.0)
        val quote = service.quote(input(VehicleType.CAR).copy(route = route))
        assertEquals(45000L, quote.estimatedFare)
        assertEquals(5L, quote.estimatedDurationMinutes)
        assertEquals(route, quote.route)
        Mockito.verifyNoInteractions(routes)
    }}
