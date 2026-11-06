package com.nhom15.datxebackend.ride

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.config.properties.ServiceAreaProperties
import com.nhom15.datxebackend.ride.dto.request.CreateRideRequest
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.service.RideCreationValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus

class RideCreationValidatorTests {
    private val validator = RideCreationValidator(
        ServiceAreaProperties(centerLat = 21.0285, centerLng = 105.8542, radiusKm = 2.0),
    )

    @Test
    fun `ride inside the service area is accepted`() {
        validator.validate(request(21.0285, 105.8542))
    }

    @Test
    fun `ride outside the service area is rejected with 422`() {
        val error = assertThrows<AppException> { validator.validate(request(21.15, 105.8542)) }

        assertEquals(ErrorCode.OUT_OF_SERVICE_AREA, error.errorCode)
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.httpStatus)
    }

    @Test
    fun `ride with dropoff outside the service area is rejected`() {
        val request = CreateRideRequest(
            vehicleId = 1,
            type = RideType.INSTANT,
            pickupAddress = "Điểm đón",
            pickupLat = 21.0285,
            pickupLng = 105.8542,
            dropoffAddress = "Điểm trả xa",
            dropoffLat = 21.15,
            dropoffLng = 105.8542,
            paymentMethod = PaymentMethod.CASH,
        )

        val error = assertThrows<AppException> { validator.validate(request) }
        assertEquals(ErrorCode.OUT_OF_SERVICE_AREA, error.errorCode)
    }

    private fun request(pickupLat: Double, pickupLng: Double) = CreateRideRequest(
        vehicleId = 1,
        type = RideType.INSTANT,
        pickupAddress = "Điểm đón",
        pickupLat = pickupLat,
        pickupLng = pickupLng,
        dropoffAddress = "Điểm trả",
        dropoffLat = pickupLat + 0.001,
        dropoffLng = pickupLng + 0.001,
        paymentMethod = PaymentMethod.CASH,
    )
}
