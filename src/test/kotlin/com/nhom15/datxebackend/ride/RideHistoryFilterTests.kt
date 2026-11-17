package com.nhom15.datxebackend.ride

import com.nhom15.datxebackend.chat.service.ChatService
import com.nhom15.datxebackend.common.api.PageResponse
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.domain.Location
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.GlobalExceptionHandler
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.payment.service.PaymentService
import com.nhom15.datxebackend.ride.controller.RideController
import com.nhom15.datxebackend.ride.dto.request.RideListFilter
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.repository.HandoverPhotoRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.ride.repository.RideSpecifications
import com.nhom15.datxebackend.ride.repository.RideStatusHistoryRepository
import com.nhom15.datxebackend.ride.repository.VehicleHandoverReportRepository
import com.nhom15.datxebackend.ride.service.RideEstimateService
import com.nhom15.datxebackend.ride.service.RideQueryService
import com.nhom15.datxebackend.ride.service.RideService
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.CriteriaQuery
import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.Path
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant

class RideHistoryFilterTests {
    private val rides = Mockito.mock(RideRepository::class.java)
    private val service = RideQueryService(
        rides,
        Mockito.mock(RideStatusHistoryRepository::class.java),
        Mockito.mock(VehicleHandoverReportRepository::class.java),
        Mockito.mock(HandoverPhotoRepository::class.java),
        Mockito.mock(ChatService::class.java),
        Mockito.mock(CustomerRepository::class.java),
        Mockito.mock(DriverRepository::class.java),
        Mockito.mock(PaymentService::class.java),
    )

    private fun sampleRide(id: Long, createdAt: Instant): Ride = Ride(
        customerId = 10L,
        driverId = 20L,
        vehicleId = 30L,
        status = RideStatus.COMPLETED,
        type = RideType.INSTANT,
        pickup = Location(GeoPoint(21.0285, 105.8542), "18 Nguyễn Chí Thanh, Đống Đa"),
        dropoff = Location(GeoPoint(21.0365, 105.7820), "144 Xuân Thủy, Cầu Giấy"),
        vehicleType = VehicleType.CAR,
        transmission = Transmission.AUTO,
        licensePlate = "30A-12345",
        fareEstimate = 120_000L,
        paymentMethod = PaymentMethod.CASH,
    ).apply {
        this.id = id
        this.createdAt = createdAt
    }

    @Test
    fun `listMyRides filters via Specification before pagination and preserves metadata and sort order`() {
        val driver = AuthUser(20L, "drv-uid", Role.DRIVER, "Tài Xế", null, null, "ONLINE")
        val from = Instant.parse("2026-11-10T17:00:00Z")
        val to = Instant.parse("2026-11-17T17:00:00Z")
        val filter = RideListFilter(
            status = "COMPLETED,CANCELLED",
            page = 1,
            size = 2,
            q = "Cầu Giấy",
            createdFrom = from,
            createdTo = to,
        )
        val expectedSort = Sort.by(Sort.Direction.DESC, "createdAt", "id")
        val expectedPageable = PageRequest.of(1, 2, expectedSort)
        val filteredRows = listOf(sampleRide(102L, from.plusSeconds(120)), sampleRide(101L, from.plusSeconds(60)))
        val page = PageImpl(filteredRows, expectedPageable, 5L)

        Mockito.`when`(rides.findAll(any<Specification<Ride>>(), eq(expectedPageable))).thenReturn(page)

        val result = service.listMyRides(driver, filter)
        assertEquals(1, result.page)
        assertEquals(2, result.size)
        assertEquals(5L, result.totalElements)
        assertEquals(3, result.totalPages)
        assertTrue(result.hasNext)
        assertEquals(listOf(102L, 101L), result.content.map { it.id })

        val pageableCaptor = ArgumentCaptor.forClass(Pageable::class.java)
        @Suppress("UNCHECKED_CAST")
        val specCaptor = ArgumentCaptor.forClass(Specification::class.java) as ArgumentCaptor<Specification<Ride>>
        Mockito.verify(rides).findAll(specCaptor.capture(), pageableCaptor.capture())
        assertEquals(expectedSort, pageableCaptor.value.sort)
    }

    @Test
    fun `listMyRides rejects invalid date range, oversized query, bad status, negative page and admin role`() {
        val driver = AuthUser(20L, "drv-uid", Role.DRIVER, "Tài Xế", null, null, "ONLINE")
        val admin = AuthUser(1L, "adm-uid", Role.ADMIN, "Admin", null, null, "ACTIVE")
        val t1 = Instant.parse("2026-11-10T17:00:00Z")
        val t2 = Instant.parse("2026-11-11T17:00:00Z")

        assertThrows(ForbiddenException::class.java) { service.listMyRides(admin, RideListFilter()) }
        assertThrows(BadRequestException::class.java) { service.listMyRides(driver, RideListFilter(page = -1)) }
        assertThrows(BadRequestException::class.java) { service.listMyRides(driver, RideListFilter(createdFrom = t2, createdTo = t1)) }
        assertThrows(BadRequestException::class.java) { service.listMyRides(driver, RideListFilter(createdFrom = t1, createdTo = t1)) }
        assertThrows(BadRequestException::class.java) { service.listMyRides(driver, RideListFilter(q = "a".repeat(201))) }
        assertThrows(BadRequestException::class.java) { service.listMyRides(driver, RideListFilter(status = "INVALID")) }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `RideSpecifications applies actor scoping, half-open created boundaries and escaped keyword search`() {
        val from = Instant.parse("2026-11-10T17:00:00Z")
        val to = Instant.parse("2026-11-17T17:00:00Z")
        val filter = RideListFilter(status = "COMPLETED", q = "#100%_!", createdFrom = from, createdTo = to)
        val spec = RideSpecifications.forUser(20L, Role.DRIVER, filter, listOf(RideStatus.COMPLETED))

        val root = Mockito.mock(Root::class.java) as Root<Ride>
        val query = Mockito.mock(CriteriaQuery::class.java) as CriteriaQuery<*>
        val cb = Mockito.mock(CriteriaBuilder::class.java)

        val driverPath = Mockito.mock(Path::class.java) as Path<Long>
        val statusPath = Mockito.mock(Path::class.java) as Path<RideStatus>
        val createdAtPath = Mockito.mock(Path::class.java) as Path<Instant>
        val pickupPath = Mockito.mock(Path::class.java) as Path<Location>
        val dropoffPath = Mockito.mock(Path::class.java) as Path<Location>
        val pickupAddrPath = Mockito.mock(Path::class.java) as Path<String>
        val dropoffAddrPath = Mockito.mock(Path::class.java) as Path<String>
        val platePath = Mockito.mock(Path::class.java) as Path<String>
        val expr = Mockito.mock(Expression::class.java) as Expression<String>
        val pred = Mockito.mock(Predicate::class.java)

        Mockito.`when`(root.get<Long>("driverId")).thenReturn(driverPath)
        Mockito.`when`(root.get<RideStatus>("status")).thenReturn(statusPath)
        Mockito.`when`(root.get<Instant>("createdAt")).thenReturn(createdAtPath)
        Mockito.`when`(root.get<Location>("pickup")).thenReturn(pickupPath)
        Mockito.`when`(root.get<Location>("dropoff")).thenReturn(dropoffPath)
        Mockito.`when`(pickupPath.get<String>("address")).thenReturn(pickupAddrPath)
        Mockito.`when`(dropoffPath.get<String>("address")).thenReturn(dropoffAddrPath)
        Mockito.`when`(root.get<String>("licensePlate")).thenReturn(platePath)
        Mockito.`when`(cb.equal(any<Expression<*>>(), any())).thenReturn(pred)
        Mockito.`when`(statusPath.`in`(any<Collection<*>>())).thenReturn(pred)
        Mockito.`when`(cb.greaterThanOrEqualTo(createdAtPath, from)).thenReturn(pred)
        Mockito.`when`(cb.lessThan(createdAtPath, to)).thenReturn(pred)
        Mockito.`when`(cb.coalesce(any<Expression<String>>(), any<String>())).thenReturn(expr)
        Mockito.`when`(cb.lower(any())).thenReturn(expr)
        Mockito.`when`(cb.like(any<Expression<String>>(), any<String>(), eq('!'))).thenReturn(pred)
        Mockito.`when`(cb.or(any(), any(), any())).thenReturn(pred)
        Mockito.`when`(cb.and(any(), any(), any(), any(), any())).thenReturn(pred)

        spec.toPredicate(root, query, cb)

        Mockito.verify(cb).equal(driverPath, 20L)
        Mockito.verify(cb).greaterThanOrEqualTo(createdAtPath, from)
        Mockito.verify(cb).lessThan(createdAtPath, to)
        Mockito.verify(cb, Mockito.times(3)).like(any<Expression<String>>(), eq("%#100!%!_!!%"), eq('!'))
    }

    @Test
    fun `customer and driver rides endpoints bind q, status, createdFrom and createdTo at HTTP boundary`() {
        val queryService = Mockito.mock(RideQueryService::class.java)
        val controller = RideController(
            Mockito.mock(RideService::class.java),
            queryService,
            Mockito.mock(RideEstimateService::class.java),
        )
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .setMessageConverters(JacksonJsonHttpMessageConverter(mapper))
            .build()

        val driver = AuthUser(20L, "drv", Role.DRIVER, "Tài Xế", null, null, "ONLINE")
        val from = Instant.parse("2026-11-10T17:00:00Z")
        val to = Instant.parse("2026-11-17T17:00:00Z")
        val expectedFilter = RideListFilter(
            status = "COMPLETED",
            page = 0,
            size = 20,
            q = "30A-123",
            createdFrom = from,
            createdTo = to,
        )
        Mockito.`when`(queryService.listMyRides(driver, expectedFilter)).thenReturn(PageResponse(emptyList(), 0, 20, 0L, 0, false))

        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(driver, null)
        try {
            mvc.perform(
                get("/api/driver/rides")
                    .param("status", "COMPLETED")
                    .param("page", "0")
                    .param("size", "20")
                    .param("q", "30A-123")
                    .param("createdFrom", "2026-11-10T17:00:00.000Z")
                    .param("createdTo", "2026-11-17T17:00:00.000Z"),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.size").value(20))

            Mockito.verify(queryService).listMyRides(driver, expectedFilter)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }
}
