package com.nhom15.datxebackend.common.redis

object RedisKeys {

    const val GEO_KEY = "available_drivers"
    private const val ALIVE_PREFIX = "driver:alive:"

    fun driverAlive(id: String) = "$ALIVE_PREFIX$id"
    fun driverStatus(id: String) = "driver:$id:status"
    fun driverLocation(id: String) = "driver:$id:location"
    fun driverLock(id: String) = "driver:$id:lock"
    fun driverCurrentRide(id: String) = "driver:$id:current_ride"
    fun driverStats(id: String) = "driver:$id:stats"

    fun rideState(id: String) = "ride:$id:state"
    fun rideCandidates(id: String) = "ride:$id:candidates"
    fun rideRejected(id: String) = "ride:$id:rejected"
    fun rideTrack(id: String) = "ride:$id:track"
    fun rideEvents(id: String) = "ride:$id:events"
    const val DISPATCH_TIMEOUTS = "dispatch:timeouts"
    const val SCHEDULED_RIDES = "scheduled:rides"

    fun customerActiveRide(id: String) = "customer:$id:active_ride"
    fun rideQuoteRate(id: String) = "rate:ride-quote:$id"

    fun idempotency(key: String) = "idem:$key"
    fun rideLock(id: String) = "lock:ride:$id"
    fun chatRate(role: com.nhom15.datxebackend.common.enums.Role, id: Long) = "chat:rate:${role.name}:$id"
    fun mapboxRate(purpose: String, role: com.nhom15.datxebackend.common.enums.Role, id: Long) =
        "mapbox:rate:$purpose:${role.name}:$id"
    fun authLoginRate(ip: String) = "auth:login:rate:$ip"
    fun mapboxGeocodeCache(hash: String) = "mapbox:cache:geocode:$hash"
    fun mapboxDirectionsCache(hash: String) = "mapbox:cache:directions:$hash"
}

