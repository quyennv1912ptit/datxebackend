package com.nhom15.datxebackend.routing

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.routing.api.DrivingRouteProvider
import com.nhom15.datxebackend.routing.model.DrivingRoute
import com.nhom15.datxebackend.routing.model.SavedRideRoute
import com.nhom15.datxebackend.routing.repository.RideRouteRepository
import com.nhom15.datxebackend.routing.service.RideRouteService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class RideRouteSnapshotTests {
    private val provider = Mockito.mock(DrivingRouteProvider::class.java)
    private val repository = Mockito.mock(RideRouteRepository::class.java)
    private val service = RideRouteService(provider, repository)
    private val pickup = GeoPoint(21.030123456, 105.840567891)
    private val dropoff = GeoPoint(21.041234567, 105.851234567)
    @Test fun `estimate refresh and create reuse one stored route`() {
        Mockito.`when`(provider.findRoute(pickup, dropoff, VehicleType.CAR, true)).thenReturn(DrivingRoute(1234.56, 125.0, coordinates = listOf(pickup, dropoff), provider = "goong"))
        val first = service.resolve(1, 7, VehicleType.CAR, pickup, dropoff)
        Mockito.`when`(repository.find(Mockito.anyString())).thenReturn(first)
        val refreshed = service.resolve(1, 7, VehicleType.CAR, pickup, dropoff, first.id)
        val created = service.resolve(1, 7, VehicleType.CAR, pickup, dropoff, first.id)
        assertEquals(first, refreshed); assertEquals(first, created)
        assertEquals(1234.56, created.distanceMeters)
        assertEquals(125.0, created.durationSeconds)
        Mockito.verify(provider, Mockito.times(1)).findRoute(pickup, dropoff, VehicleType.CAR, true)
        val saved = Mockito.mockingDetails(repository).invocations.single { it.method.name == "save" }.arguments[1] as SavedRideRoute
        assertEquals(first.polyline, saved.polyline)
    }
    @Test fun `stale or mismatched quote cannot silently change route while creating`() {
        assertThrows(ConflictException::class.java) { service.resolve(2, 7, VehicleType.MOTORCYCLE, pickup, dropoff, "old-or-foreign-quote") }
        Mockito.verifyNoInteractions(provider)
    }
}
