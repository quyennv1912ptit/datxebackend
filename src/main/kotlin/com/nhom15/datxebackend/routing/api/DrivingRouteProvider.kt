package com.nhom15.datxebackend.routing.api

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.routing.model.DrivingRoute

/** Cung cấp quãng đường chạy xe thực tế để tính giá ride. */
interface DrivingRouteProvider {
    fun findRoute(
        pickup: GeoPoint,
        dropoff: GeoPoint,
        vehicleType: VehicleType,
        includeGeometry: Boolean,
    ): DrivingRoute

    fun findRoute(pickup: GeoPoint, dropoff: GeoPoint, vehicleType: VehicleType): DrivingRoute =
        findRoute(pickup, dropoff, vehicleType, includeGeometry = true)
}
