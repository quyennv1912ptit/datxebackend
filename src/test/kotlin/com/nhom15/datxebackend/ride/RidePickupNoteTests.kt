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
class RidePickupNoteTests {
    @Test fun `pickup note is trimmed persisted separately and returned to assigned driver`() {
        val request = CreateRideRequest(vehicleId=7, pickupAddress="Ngõ 165 Cầu Giấy", pickupLat=21.03, pickupLng=105.84, dropoffAddress="Hồ Tây", dropoffLat=21.06, dropoffLng=105.82, note="Ghi chú chuyến", pickupNote="  Chờ ở đầu ngõ, cổng số 2  ")
        val vehicle = CustomerVehicleSnapshot(7,1,Transmission.AUTO,VehicleType.CAR,"30A-12345")
        val quote = RideQuote(1,BigDecimal("2.5"),5,20000,25000,0,0,0,45000)
        val ride = RideFactory.create(request,1,vehicle,quote,0)
        assertEquals("Chờ ở đầu ngõ, cổng số 2",ride.pickupNote)
        assertEquals("Ghi chú chuyến",ride.note)
        assertEquals(ride.pickupNote,RideResponseMapper.detail(ride,emptyList(),emptyList()).pickupNote)
        assertNull(RideFactory.create(request.copy(pickupNote=" "),1,vehicle,quote,0).pickupNote)
    }
}
