package com.nhom15.datxebackend.admin

import com.nhom15.datxebackend.admin.service.AdminDashboardService
import com.nhom15.datxebackend.common.exception.BadRequestException
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Properties
import java.util.UUID

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class AdminDashboardPostgresTests {
    @Test
    fun `reports local dates, recorded commissions and wallet liabilities without double counting`() {
        val schema = "admin_dashboard_" + UUID.randomUUID().toString().replace("-", "")
        val ds = DriverManagerDataSource(System.getenv("TEST_DB_URL"), System.getenv("TEST_DB_USER"), System.getenv("TEST_DB_PASSWORD"))
        val admin = JdbcTemplate(ds)
        try {
            Flyway.configure().dataSource(ds).schemas(schema).load().migrate()
            ds.connectionProperties = Properties().apply { setProperty("currentSchema", schema) }
            val jdbc = JdbcTemplate(ds)
            jdbc.execute("""
                INSERT INTO customers(id,firebase_uid,full_name,phone_number,status) VALUES(1,'dash-c1','Khách','0901111111','ACTIVE');
                INSERT INTO drivers(id,firebase_uid,full_name,phone_number,status) VALUES
                    (1,'dash-d1','Tài xế','0912345678','ONLINE'), (2,'dash-d2','Tài xế 2','0912345679','OFFLINE');
                INSERT INTO vehicles(id,customer_id,plate_number,vehicle_type,transmission_type) VALUES(1,1,'DASH-01','CAR','AUTO');
                INSERT INTO rides(id,customer_id,driver_id,vehicle_id,status,type,vehicle_type,transmission,fare_estimate,final_fare,discount_amount,payment_method,
                                  pickup_lat,pickup_lng,pickup_address,dropoff_lat,dropoff_lng,dropoff_address,created_at,completed_at,cancelled_at,cancelled_by_role)
                SELECT id,1,1,1,status,'INSTANT','CAR','AUTO',gross,net,discount,method,21,105,'Đón',21,105,'Trả',created,completed,cancelled,actor
                FROM (VALUES
                    (11,'COMPLETED',1200000,1100000,100000,'WALLET',timestamptz '2026-11-01 16:59:59+00',timestamptz '2026-11-01 17:00:00+00',null,null),
                    (12,'COMPLETED',50000,50000,0,'CASH','2026-11-08 02:00:00+00','2026-11-08 02:15:00+00',null,null),
                    (13,'COMPLETED',30000,null,0,'MOMO','2026-11-08 02:30:00+00',null,null,null),
                    (14,'CANCELLED',50000,null,0,'CASH','2026-11-08 03:00:00+00',null,timestamptz '2026-11-08 03:01:00+00','CUSTOMER'),
                    (15,'CANCELLED',50000,null,0,'CASH','2026-11-08 03:02:00+00',null,'2026-11-08 03:03:00+00','CUSTOMER'),
                    (16,'CANCELLED',50000,null,0,'CASH','2026-11-08 03:04:00+00',null,'2026-11-08 03:05:00+00','CUSTOMER'),
                    (17,'PENDING',50000,null,0,'CASH','2026-11-01 04:00:00+00',null,null,null)
                ) AS input(id,status,gross,net,discount,method,created,completed,cancelled,actor);
                INSERT INTO payments(ride_id,customer_id,driver_id,amount,method,status) VALUES
                    (11,1,1,1100000,'WALLET','PAID'), (12,1,1,50000,'CASH','PENDING');
                INSERT INTO wallet_transactions(driver_id,wallet_type,amount,type,transaction_type,status,ride_id) VALUES
                    (1,'CREDIT',-220000,'COMMISSION_FEE','COMMISSION_FEE','SUCCESS',11),
                    (1,'CREDIT',220000,'REFUND','REFUND','SUCCESS',11),
                    (1,'CASH',880000,'TRIP_EARNING','RIDE_PAYMENT','SUCCESS',11),
                    (1,'CREDIT',-10000,'COMMISSION_FEE','COMMISSION_FEE','SUCCESS',12);
                UPDATE wallets SET balance = CASE wallet_type WHEN 'CUSTOMER' THEN 30000 WHEN 'CASH' THEN 90000 ELSE 50000 END WHERE user_id=1;
                UPDATE wallets SET balance=-10000 WHERE user_id=2 AND wallet_type='CREDIT';
                INSERT INTO withdrawal_requests(driver_id,amount,idempotency_key) VALUES (1,40000,'dash-1');
            """.trimIndent())
            val clock = Clock.fixed(Instant.parse("2026-11-08T05:00:00Z"), ZoneOffset.UTC)
            val service = AdminDashboardService(jdbc, clock)
            val report = service.summary(7)

            assertEquals("2026-11-02", report.daily.first().day.toString())
            assertEquals(7, report.daily.size)
            assertEquals(1_200_000L, report.daily.first().gmv)
            assertEquals(0L, report.daily[1].gmv)
            assertEquals(1_280_000L, report.gmv)
            assertEquals(230_000L, report.revenue)
            assertEquals(100_000L, report.promoCost)
            assertEquals(130_000L, report.grossProfit)
            assertEquals(5L, report.tripCount)
            assertEquals(1L, report.previousTripCount)
            assertEquals(listOf(1L, 1L, 1L), with(report.paymentMix) { listOf(cash, wallet, other) })
            assertEquals(listOf(90_000L, 30_000L, 50_000L, 40_000L), with(report.wallets) {
                listOf(driverCash, customer, driverCredit, pendingWithdrawals)
            })
            assertEquals(setOf("HIGH_FARE", "REPEATED_CANCELLATIONS"), report.anomalies.map { it.kind }.toSet())
            assertEquals(30, service.summary(30).daily.size)
            assertThrows(BadRequestException::class.java) { service.summary(14) }
        } finally {
            ds.connectionProperties = Properties()
            admin.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }
}
