package com.nhom15.datxebackend.routing.model

import com.nhom15.datxebackend.common.domain.GeoPoint
import kotlin.math.pow
import kotlin.math.roundToLong

object PolylineCodec {
    fun decode(value: String, precision: Int = 5): List<GeoPoint> {
        val scale = 10.0.pow(precision)
        var index = 0; var lat = 0L; var lng = 0L
        fun read(): Long {
            var result = 0L; var shift = 0
            while (true) {
                require(index < value.length && shift <= 60) { "Invalid polyline" }
                val byte = value[index++].code - 63
                require(byte in 0..63)
                result = result or ((byte and 31).toLong() shl shift)
                if (byte < 32) break
                shift += 5
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        return buildList {
            while (index < value.length) {
                lat += read(); lng += read()
                val point = GeoPoint(lat / scale, lng / scale)
                require(point.lat in -90.0..90.0 && point.lng in -180.0..180.0)
                add(point)
            }
        }
    }
    fun encode(points: List<GeoPoint>, precision: Int = 6): String = buildString {
        val scale = 10.0.pow(precision); var previousLat = 0L; var previousLng = 0L
        fun write(delta: Long) {
            var value = if (delta < 0) (delta shl 1).inv() else delta shl 1
            while (value >= 32) { append(((32 or (value.toInt() and 31)) + 63).toChar()); value = value shr 5 }
            append((value.toInt() + 63).toChar())
        }
        points.forEach { point ->
            val lat = (point.lat * scale).roundToLong(); val lng = (point.lng * scale).roundToLong()
            write(lat - previousLat); write(lng - previousLng); previousLat = lat; previousLng = lng
        }
    }
}
