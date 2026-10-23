package com.nhom15.datxebackend.vehicle.repository

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.vehicle.dto.CreateVehicleRequest
import com.nhom15.datxebackend.vehicle.dto.UpdateVehicleRequest
import com.nhom15.datxebackend.vehicle.dto.VehicleImageResponse
import com.nhom15.datxebackend.vehicle.dto.VehicleResponse
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository

@Repository
class JdbcVehicleRepository(private val jdbcTemplate: JdbcTemplate) {

    fun findAllByOwnerId(ownerId: Long): List<VehicleResponse> {
        val vehicles = jdbcTemplate.query(
            "SELECT * FROM vehicles WHERE customer_id = ? ORDER BY id",
        { row, _ ->
            val labels = (1..row.metaData.columnCount).associate {
                row.metaData.getColumnLabel(it).lowercase() to it
            }
            fun value(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
                labels[name]?.let(row::getString)
            }
            VehicleResponse(
                id = row.getLong("id"),
                plateNumber = value("plate_number"),
                transmissionType = value("transmission_type"),
                vehicleType = value("vehicle_type") ?: "CAR",
                engineCc = if ("engine_cc" in labels) row.getObject("engine_cc", Int::class.javaObjectType) else null,
                brand = value("brand", "make", "manufacturer", "brand_model"),
                model = value("model"),
                color = value("color", "colour"),
            )
        },
        ownerId,
        )
        val imagesByVehicle = findImagesByOwnerId(ownerId).groupBy { it.first }
        return vehicles.map { vehicle ->
            vehicle.copy(images = imagesByVehicle[vehicle.id].orEmpty().map { it.second })
        }
    }

    fun create(ownerId: Long, request: CreateVehicleRequest): VehicleResponse {
        val columns = vehicleColumns()
        val selected = linkedMapOf<String, Any>(
            "customer_id" to ownerId,
            "plate_number" to request.plateNumber.trim().uppercase(),
            "transmission_type" to request.transmissionType.name,
        )
        when {
            request.vehicleType == VehicleType.MOTORCYCLE && (request.engineCc == null || request.engineCc <= 0) ->
                throw BadRequestException(message = "Xe máy cần khai báo dung tích xi-lanh engine_cc lớn hơn 0")
            request.vehicleType == VehicleType.CAR && request.engineCc != null ->
                throw BadRequestException(message = "engine_cc chỉ áp dụng cho xe máy")
        }
        if ("vehicle_type" in columns) {
            selected["vehicle_type"] = request.vehicleType.name
        } else if (request.vehicleType != VehicleType.CAR) {
            throw BadRequestException(message = "Cơ sở dữ liệu hiện chỉ hỗ trợ loại xe CAR")
        }
        if (request.engineCc != null) {
            if ("engine_cc" !in columns) {
                throw BadRequestException(message = "Cơ sở dữ liệu chưa hỗ trợ engine_cc; hãy chạy migration mới nhất")
            }
            selected["engine_cc"] = request.engineCc
        }
        addOptional(selected, columns, listOf("brand", "make", "manufacturer"), request.brand, "hãng xe")
        addOptional(selected, columns, listOf("model"), request.model, "mẫu xe")
        addOptional(selected, columns, listOf("color", "colour"), request.color, "màu xe")

        val missingRequired = listOf("customer_id", "plate_number", "transmission_type")
            .filterNot(columns::contains)
        if (missingRequired.isNotEmpty()) {
            throw BadRequestException(message = "Bảng vehicles thiếu cột bắt buộc: ${missingRequired.joinToString()}")
        }

        val keyHolder = GeneratedKeyHolder()
        val names = selected.keys.toList()
        val sql = "INSERT INTO vehicles (${names.joinToString()}) VALUES (${names.joinToString { "?" }})"
        try {
            jdbcTemplate.update({ connection ->
                connection.prepareStatement(sql, arrayOf("id")).apply {
                    names.forEachIndexed { index, name -> setObject(index + 1, selected.getValue(name)) }
                }
            }, keyHolder)
        } catch (e: DataIntegrityViolationException) {
            throw BadRequestException(message = "Biển số xe đã được đăng ký hoặc dữ liệu xe không hợp lệ")
        }
        val id = keyHolder.key?.toLong()
            ?: throw IllegalStateException("Không lấy được id xe mới")
        return findByIdAndOwnerId(id, ownerId) ?: throw IllegalStateException("Không đọc lại được xe vừa tạo")
    }

    fun deleteByIdAndOwnerId(id: Long, ownerId: Long): List<String> {
        if (!lockOwnedVehicle(id, ownerId)) throw NotFoundException(ErrorCode.VEHICLE_NOT_FOUND)
        val publicIds = jdbcTemplate.query(
            "SELECT cloudinary_public_id FROM vehicle_images WHERE vehicle_id = ?",
            { row, _ -> row.getString("cloudinary_public_id") },
            id,
        )
        val deleted = try {
            jdbcTemplate.update("DELETE FROM vehicles WHERE id = ? AND customer_id = ?", id, ownerId)
        } catch (_: DataIntegrityViolationException) {
            throw ConflictException(message = "Xe đã được sử dụng trong chuyến đi nên không thể xóa")
        }
        if (deleted == 0) throw NotFoundException(ErrorCode.VEHICLE_NOT_FOUND)
        return publicIds
    }

    fun updateByIdAndOwnerId(id: Long, ownerId: Long, request: UpdateVehicleRequest): VehicleResponse {
        val columns = vehicleColumns()
        when {
            request.vehicleType == VehicleType.MOTORCYCLE && (request.engineCc == null || request.engineCc <= 0) ->
                throw BadRequestException(message = "Xe máy cần khai báo dung tích xi-lanh engine_cc lớn hơn 0")
            request.vehicleType == VehicleType.CAR && request.engineCc != null ->
                throw BadRequestException(message = "engine_cc chỉ áp dụng cho xe máy")
        }

        val selected = linkedMapOf<String, Any?>(
            "plate_number" to request.plateNumber.trim().uppercase(),
            "transmission_type" to request.transmissionType.name,
        )
        if ("vehicle_type" in columns) {
            selected["vehicle_type"] = request.vehicleType.name
        } else if (request.vehicleType != VehicleType.CAR) {
            throw BadRequestException(message = "Cơ sở dữ liệu hiện chỉ hỗ trợ loại xe CAR")
        }
        if ("engine_cc" in columns) {
            selected["engine_cc"] = request.engineCc
        } else if (request.engineCc != null) {
            throw BadRequestException(message = "Cơ sở dữ liệu chưa hỗ trợ engine_cc; hãy chạy migration mới nhất")
        }
        addUpdateValue(selected, columns, listOf("brand", "make", "manufacturer", "brand_model"), request.brand, "hãng xe")
        addUpdateValue(selected, columns, listOf("model"), request.model, "mẫu xe")
        addUpdateValue(selected, columns, listOf("color", "colour"), request.color, "màu xe")

        val names = selected.keys.toList()
        val sql = "UPDATE vehicles SET ${names.joinToString { "$it = ?" }}, updated_at = now() WHERE id = ? AND customer_id = ?"
        val args = selected.values.toMutableList().apply {
            add(id)
            add(ownerId)
        }
        try {
            val updated = jdbcTemplate.update(sql, *args.toTypedArray())
            if (updated == 0) throw NotFoundException(ErrorCode.VEHICLE_NOT_FOUND)
        } catch (e: DataIntegrityViolationException) {
            val causeMessage = e.mostSpecificCause.message.orEmpty()
            when {
                "fk_rides_vehicle_type" in causeMessage ->
                    throw ConflictException(message = "Không thể đổi loại xe vì xe đã được sử dụng trong chuyến đi")
                "vehicles_plate_number_key" in causeMessage ->
                    throw ConflictException(message = "Biển số xe đã được đăng ký")
                else -> throw ConflictException(message = "Không thể cập nhật xe do dữ liệu đang được chuyến đi sử dụng")
            }
        }
        return findByIdAndOwnerId(id, ownerId) ?: throw NotFoundException(ErrorCode.VEHICLE_NOT_FOUND)
    }

    fun lockOwnedVehicle(id: Long, ownerId: Long): Boolean = jdbcTemplate.query(
        "SELECT id FROM vehicles WHERE id = ? AND customer_id = ? FOR UPDATE",
        { _, _ -> true },
        id,
        ownerId,
    ).isNotEmpty()

    fun countImages(vehicleId: Long): Int = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM vehicle_images WHERE vehicle_id = ?",
        Long::class.java,
        vehicleId,
    )?.toInt() ?: 0

    fun addImage(vehicleId: Long, secureUrl: String, publicId: String): VehicleImageResponse {
        val imageId = jdbcTemplate.queryForObject(
            "INSERT INTO vehicle_images (vehicle_id, secure_url, cloudinary_public_id) VALUES (?, ?, ?) RETURNING id",
            Long::class.java,
            vehicleId,
            secureUrl,
            publicId,
        ) ?: throw IllegalStateException("Không lấy được id ảnh xe vừa tạo")
        return VehicleImageResponse(imageId, secureUrl)
    }

    fun deleteImage(vehicleId: Long, imageId: Long, ownerId: Long): String {
        val publicId = jdbcTemplate.query(
            """SELECT image.cloudinary_public_id
               FROM vehicle_images image
               JOIN vehicles vehicle ON vehicle.id = image.vehicle_id
               WHERE image.id = ? AND image.vehicle_id = ? AND vehicle.customer_id = ?""".trimIndent(),
            { row, _ -> row.getString("cloudinary_public_id") },
            imageId,
            vehicleId,
            ownerId,
        ).firstOrNull() ?: throw NotFoundException(ErrorCode.VEHICLE_NOT_FOUND)

        val deleted = jdbcTemplate.update(
            "DELETE FROM vehicle_images WHERE id = ? AND vehicle_id = ?",
            imageId,
            vehicleId,
        )
        if (deleted == 0) throw NotFoundException(ErrorCode.VEHICLE_NOT_FOUND)
        return publicId
    }

    private fun findByIdAndOwnerId(id: Long, ownerId: Long): VehicleResponse? = jdbcTemplate.query(
        "SELECT * FROM vehicles WHERE id = ? AND customer_id = ?",
        { row, _ ->
            val labels = (1..row.metaData.columnCount).associate {
                row.metaData.getColumnLabel(it).lowercase() to it
            }
            fun value(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
                labels[name]?.let(row::getString)
            }
            VehicleResponse(
                id = row.getLong("id"), plateNumber = value("plate_number"),
                transmissionType = value("transmission_type"), vehicleType = value("vehicle_type") ?: "CAR",
                brand = value("brand", "make", "manufacturer", "brand_model"), model = value("model"),
                engineCc = if ("engine_cc" in labels) row.getObject("engine_cc", Int::class.javaObjectType) else null,
                color = value("color", "colour"),
            )
        },
        id,
        ownerId,
    ).firstOrNull()

    private fun findImagesByOwnerId(ownerId: Long): List<Pair<Long, VehicleImageResponse>> = jdbcTemplate.query(
        """SELECT image.vehicle_id, image.id, image.secure_url
           FROM vehicle_images image
           JOIN vehicles vehicle ON vehicle.id = image.vehicle_id
           WHERE vehicle.customer_id = ?
           ORDER BY image.created_at, image.id""".trimIndent(),
        { row, _ ->
            row.getLong("vehicle_id") to VehicleImageResponse(
                id = row.getLong("id"),
                secureUrl = row.getString("secure_url"),
            )
        },
        ownerId,
    )

    private fun vehicleColumns(): Set<String> = jdbcTemplate.execute(ConnectionCallback { connection ->
        val columns = mutableSetOf<String>()
        connection.metaData.getColumns(connection.catalog, null, "vehicles", null).use { rows ->
            while (rows.next()) columns += rows.getString("COLUMN_NAME").lowercase()
        }
        columns
    })

    private fun addOptional(
        selected: MutableMap<String, Any>,
        columns: Set<String>,
        aliases: List<String>,
        value: String?,
        label: String,
    ) {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return
        val column = aliases.firstOrNull(columns::contains)
        if (column == null) {
            throw BadRequestException(message = "Cơ sở dữ liệu chưa hỗ trợ lưu $label")
        }
        selected[column] = normalized
    }

    private fun addUpdateValue(
        selected: MutableMap<String, Any?>,
        columns: Set<String>,
        aliases: List<String>,
        value: String?,
        label: String,
    ) {
        val column = aliases.firstOrNull(columns::contains)
        if (column == null) {
            if (!value.isNullOrBlank()) throw BadRequestException(message = "Cơ sở dữ liệu chưa hỗ trợ lưu $label")
            return
        }
        selected[column] = value?.trim()?.takeIf(String::isNotEmpty)
    }
}
