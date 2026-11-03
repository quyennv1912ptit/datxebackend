package com.nhom15.datxebackend.ride

import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.port.CustomerVehicleSnapshot
import com.nhom15.datxebackend.pricing.model.RideQuote
import com.nhom15.datxebackend.ride.dto.request.CreateRideRequest
import com.nhom15.datxebackend.ride.dto.response.RideCustomerResponse
import com.nhom15.datxebackend.ride.dto.response.RideDriverResponse
import com.nhom15.datxebackend.ride.service.RideFactory
import com.nhom15.datxebackend.ride.service.RideResponseMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class RideDetailResponseMappingTests {

    @Test
    fun `detail response maps licensePlate, vehicleType, transmission, driver and customer`() {
        val request = CreateRideRequest(
            vehicleId = 10,
            pickupAddress = "123 Hoàng Đạo Thúy",
            pickupLat = 21.01,
            pickupLng = 105.80,
            dropoffAddress = "456 Kim Mã",
            dropoffLat = 21.03,
            dropoffLng = 105.82,
            note = "Xe số sàn",
            pickupNote = "Đỗ ở trước sảnh chung cư",
        )
        val vehicle = CustomerVehicleSnapshot(10, 100, Transmission.MANUAL, VehicleType.CAR, "29A-99999")
        val quote = RideQuote(1, BigDecimal("5.0"), 12, 30000, 50000, 0, 0, 0, 80000)
        val ride = RideFactory.create(request, 100, vehicle, quote, 0).apply {
            driverId = 200
        }

        val customer = RideCustomerResponse(
            id = 100,
            fullName = "Nguyễn Văn A",
            phoneNumber = "+84987654321",
            avatarUrl = "https://example.com/customer.jpg",
            averageRating = 4.9,
        )

        val driver = RideDriverResponse(
            id = 200,
            fullName = "Trần Bác Tài",
            phoneNumber = "+84912345678",
            avatarUrl = "https://example.com/driver.jpg",
            averageRating = 5.0,
        )

        val detail = RideResponseMapper.detail(
            ride = ride,
            statusHistory = emptyList(),
            handoverReports = emptyList(),
            unreadChatCount = 3,
            customer = customer,
            driver = driver,
        )

        assertEquals("29A-99999", detail.licensePlate)
        assertEquals(VehicleType.CAR, detail.vehicleType)
        assertEquals(Transmission.MANUAL, detail.transmission)
        assertEquals(100L, detail.customerId)
        assertEquals(200L, detail.driverId)
        assertEquals(10L, detail.vehicleId)
        assertEquals(3L, detail.unreadChatCount)
        assertNotNull(detail.customer)
        assertEquals("Nguyễn Văn A", detail.customer?.fullName)
        assertEquals("+84987654321", detail.customer?.phoneNumber)
        assertEquals(4.9, detail.customer?.averageRating)
        assertNotNull(detail.driver)
        assertEquals("Trần Bác Tài", detail.driver?.fullName)
        assertEquals("+84912345678", detail.driver?.phoneNumber)
        assertEquals(5.0, detail.driver?.averageRating)
    }

    @Test
    fun `detail response handles null driver gracefully`() {
        val request = CreateRideRequest(
            vehicleId = 5,
            pickupAddress = "Hà Nội",
            pickupLat = 21.0,
            pickupLng = 105.8,
            dropoffAddress = "Nội Bài",
            dropoffLat = 21.2,
            dropoffLng = 105.8,
        )
        val vehicle = CustomerVehicleSnapshot(5, 50, Transmission.AUTO, VehicleType.MOTORCYCLE, "29-X1 12345")
        val quote = RideQuote(2, BigDecimal("25.0"), 40, 20000, 150000, 0, 0, 0, 170000)
        val ride = RideFactory.create(request, 50, vehicle, quote, 0)

        val detail = RideResponseMapper.detail(
            ride = ride,
            statusHistory = emptyList(),
            handoverReports = emptyList(),
        )

        assertEquals("29-X1 12345", detail.licensePlate)
        assertEquals(VehicleType.MOTORCYCLE, detail.vehicleType)
        assertEquals(Transmission.AUTO, detail.transmission)
        assertNull(detail.driverId)
        assertNull(detail.driver)
        assertNull(detail.customer)
    }
}
