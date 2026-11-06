package com.nhom15.datxebackend.matching.service

import org.springframework.data.redis.core.script.DefaultRedisScript

object DispatchScripts {

    /** Create the Redis search state once; retrying a durable outbox event must not reset an offer. */
    val INITIALIZE_SEARCH = DefaultRedisScript<Long>(
        """
        local status = redis.call('HGET', KEYS[1], 'status')
        if redis.call('HGET', KEYS[1], 'generation') == ARGV[8] and (status == 'SEARCHING' or status == 'DRIVER_ASSIGNED') then return 0 end
        redis.call('DEL', KEYS[1])
        redis.call('HSET', KEYS[1],
            'status', ARGV[1],
            'customerId', ARGV[2],
            'transmission', ARGV[3],
            'vehicleType', ARGV[4],
            'pickupLat', ARGV[5],
            'pickupLng', ARGV[6],
            'nextRoundIndex', '0', 'generation', ARGV[8])
        redis.call('EXPIRE', KEYS[1], ARGV[7])
        return 1
        """.trimIndent(),
        Long::class.java,
    )

    /**
     * KEYS[1] = ride:{id}:state        (HASH)
     * KEYS[2] = ride:{id}:candidates   (LIST)
     * KEYS[3] = ride:{id}:rejected     (SET)
     * KEYS[4] = dispatch:timeouts      (ZSET)
     * ARGV[1] = rideId
     * ARGV[2] = deadline (epoch ms): mốc hết hạn lời mời, cùng số với expiresAt gửi cho app tài xế
     * ARGV[3] = offer TTL (milliseconds); ARGV[4] = rejected TTL (seconds)
     * ARGV[5] = offer UUID; ARGV[6] = current tick lease token
     *
     * Trả về driverId được mời, ROUND_EXHAUSTED nếu hết ứng viên ở vòng hiện tại,
     * WAIT nếu đã có offer đang chờ, hoặc NOT_SEARCHING nếu chuyến không còn được tìm.
     */
    val OFFER_NEXT = DefaultRedisScript<String>(
        """
        if redis.call('GET', 'ride:' .. ARGV[1] .. ':tick') ~= ARGV[6] then return 'WAIT' end
        if redis.call('HGET', KEYS[1], 'status') ~= 'SEARCHING' then return 'NOT_SEARCHING' end
        if redis.call('HEXISTS', KEYS[1], 'currentDriver') == 1 then return 'WAIT' end

        while true do
            local d = redis.call('RPOP', KEYS[2])

            if not d then
                return 'ROUND_EXHAUSTED'
            end

            local rejected = redis.call('SISMEMBER', KEYS[3], d)
            local alive    = redis.call('EXISTS', 'driver:alive:' .. d)
            local status   = redis.call('GET', 'driver:' .. d .. ':status')
            local locked   = redis.call('EXISTS', 'driver:' .. d .. ':lock')
            local busy     = redis.call('EXISTS', 'driver:' .. d .. ':current_ride')

            if rejected == 0 and alive == 1 and status == 'ONLINE' and locked == 0 and busy == 0 then
                local claimed = redis.call('SET', 'driver:' .. d .. ':lock', ARGV[1], 'NX', 'PX', ARGV[3])
                if not claimed then return 'WAIT' end
                redis.call('SET', 'ride:' .. ARGV[1] .. ':current_offer', d .. ':' .. ARGV[5], 'PX', ARGV[3])
                redis.call('SET', 'driver:' .. d .. ':status', 'OFFERED')
                redis.call('HSET', KEYS[1], 'currentDriver', d, 'currentOfferId', ARGV[5], 'offerExpiresAt', ARGV[2])
                redis.call('SADD', KEYS[3], d)
                redis.call('EXPIRE', KEYS[3], ARGV[4])
                redis.call('ZADD', KEYS[4], ARGV[2], ARGV[1] .. ':' .. d .. ':' .. ARGV[5])
                redis.call('ZREM', 'available_drivers', d)
                redis.call('HINCRBY', 'driver:' .. d .. ':stats', 'offered', 1)
                return d
            end
        end
        """.trimIndent(),
        String::class.java
    )

    /** Return the next radius, or finish only after every configured round. */
    val ADVANCE_ROUND = DefaultRedisScript<String>(
        """
        if redis.call('HGET', KEYS[1], 'status') ~= 'SEARCHING' then return 'NOT_SEARCHING' end
        if redis.call('LLEN', KEYS[2]) > 0
           or redis.call('HEXISTS', KEYS[1], 'currentDriver') == 1 then
            return 'WAIT'
        end

        local round = tonumber(redis.call('HGET', KEYS[1], 'nextRoundIndex')) or 0
        if not redis.call('HGET', KEYS[1], 'pickupLat')
           or not redis.call('HGET', KEYS[1], 'pickupLng') then
            redis.call('HSET', KEYS[1], 'status', 'NO_DRIVER_FOUND')
            redis.call('HDEL', KEYS[1], 'currentDriver')
            return 'FINISHED'
        end

        return tostring(round) .. ':' .. ARGV[math.min(round + 1, #ARGV)]
        """.trimIndent(),
        String::class.java,
    )

    /** Commit one round's ranked candidates and advance the cursor atomically. */
    val COMPLETE_ROUND = DefaultRedisScript<Long>(
        """
        if redis.call('HGET', KEYS[1], 'status') ~= 'SEARCHING'
           or tonumber(redis.call('HGET', KEYS[1], 'nextRoundIndex')) ~= tonumber(ARGV[1]) then
            return 0
        end
        if redis.call('LLEN', KEYS[2]) > 0
           or redis.call('HEXISTS', KEYS[1], 'currentDriver') == 1 then
            return 0
        end

        for i = 3, #ARGV do
            redis.call('LPUSH', KEYS[2], ARGV[i])
        end
        if #ARGV > 2 then redis.call('EXPIRE', KEYS[2], ARGV[2]) end

        redis.call('HSET', KEYS[1], 'nextRoundIndex', tonumber(ARGV[1]) + 1)
        return 1
        """.trimIndent(),
        Long::class.java,
    )

    /**
     * KEYS[1] = ride:{id}:state        (HASH)
     * KEYS[2] = dispatch:timeouts      (ZSET)
     * KEYS[3] = ride:{id}:candidates   (LIST)
     * ARGV[1] = driverId
     * ARGV[2] = rideId
     * ARGV[3] = now (epoch ms)
     *
     * Trả 1 nếu nhận thành công, 0 nếu hết hạn / không còn là tài xế được mời.
     */
    val ACCEPT = DefaultRedisScript<Long>(
        """
        if redis.call('HGET', KEYS[1], 'status') ~= 'SEARCHING'
           or redis.call('HGET', KEYS[1], 'currentDriver') ~= ARGV[1]
           or redis.call('HGET', KEYS[1], 'currentOfferId') ~= ARGV[4]
           or redis.call('GET', 'ride:' .. ARGV[2] .. ':current_offer') ~= ARGV[1] .. ':' .. ARGV[4]
           or redis.call('GET', 'driver:' .. ARGV[1] .. ':lock') ~= ARGV[2] then
            return 0
        end

        local deadline = redis.call('ZSCORE', KEYS[2], ARGV[2] .. ':' .. ARGV[1] .. ':' .. ARGV[4])
        if not deadline or tonumber(deadline) <= tonumber(ARGV[3]) then
            return 0
        end

        redis.call('HSET', KEYS[1], 'status', 'DRIVER_ASSIGNED', 'driverId', ARGV[1], 'acceptedAt', ARGV[3])
        redis.call('HDEL', KEYS[1], 'currentDriver')

        redis.call('SET', 'driver:' .. ARGV[1] .. ':status', 'BUSY')
        redis.call('ZREM', 'available_drivers', ARGV[1])
        redis.call('DEL', 'ride:' .. ARGV[2] .. ':current_offer')
        redis.call('SET', 'driver:' .. ARGV[1] .. ':current_ride', ARGV[2])
        if redis.call('GET', 'driver:' .. ARGV[1] .. ':lock') == ARGV[2] then redis.call('DEL', 'driver:' .. ARGV[1] .. ':lock') end

        redis.call('ZREM', KEYS[2], ARGV[2] .. ':' .. ARGV[1] .. ':' .. ARGV[4])
        redis.call('HINCRBY', 'driver:' .. ARGV[1] .. ':stats', 'accepted', 1)

        redis.call('XADD', 'ride:' .. ARGV[2] .. ':events', '*',
            'event', 'DRIVER_ASSIGNED', 'driverId', ARGV[1], 'timestamp', ARGV[3])

        return 1
        """.trimIndent(),
        Long::class.java
    )

    /** Roll back Redis acceptance if the ride database did not assign this driver. */
    val ROLLBACK_ACCEPT = DefaultRedisScript<Long>(
        """
        if redis.call('HGET', KEYS[1], 'status') ~= 'DRIVER_ASSIGNED'
           or redis.call('HGET', KEYS[1], 'driverId') ~= ARGV[1] then
            return 0
        end

        if ARGV[3] == 'MISSING' then
            redis.call('DEL', KEYS[1])
        else
            redis.call('HSET', KEYS[1], 'status', ARGV[3])
            redis.call('HDEL', KEYS[1], 'driverId', 'acceptedAt')
        end
        local token = redis.call('HGET', KEYS[1], 'currentOfferId')
        if token then redis.call('ZREM', KEYS[2], ARGV[2] .. ':' .. ARGV[1] .. ':' .. token) end
        redis.call('DEL', 'ride:' .. ARGV[2] .. ':current_offer')
        if redis.call('GET', 'driver:' .. ARGV[1] .. ':lock') == ARGV[2] then redis.call('DEL', 'driver:' .. ARGV[1] .. ':lock') end
        redis.call('HINCRBY', 'driver:' .. ARGV[1] .. ':stats', 'accepted', -1)
        if redis.call('GET', 'driver:' .. ARGV[1] .. ':current_ride') == ARGV[2] then
            redis.call('DEL', 'driver:' .. ARGV[1] .. ':current_ride')
        end

        if ARGV[3] == 'SEARCHING' then
            redis.call('SADD', KEYS[3], ARGV[1])
            redis.call('EXPIRE', KEYS[3], ARGV[4])
        else
            redis.call('DEL', KEYS[3])
            redis.call('DEL', KEYS[4])
        end

        if redis.call('EXISTS', 'driver:' .. ARGV[1] .. ':current_ride') == 1 or redis.call('EXISTS', 'driver:' .. ARGV[1] .. ':lock') == 1 then return 1 end
        if redis.call('EXISTS', 'driver:alive:' .. ARGV[1]) == 1 and redis.call('EXISTS', 'driver:' .. ARGV[1] .. ':current_ride') == 0 and redis.call('EXISTS', 'driver:' .. ARGV[1] .. ':lock') == 0 then
            redis.call('SET', 'driver:' .. ARGV[1] .. ':status', 'ONLINE')
            local lat = redis.call('HGET', 'driver:' .. ARGV[1] .. ':location', 'lat')
            local lng = redis.call('HGET', 'driver:' .. ARGV[1] .. ':location', 'lng')
            if lat and lng then redis.call('GEOADD', KEYS[5], lng, lat, ARGV[1]) end
        else
            redis.call('SET', 'driver:' .. ARGV[1] .. ':status', 'OFFLINE')
        end

        return 1
        """.trimIndent(),
        Long::class.java,
    )

    /**
     * Từ chối, hết hạn hoặc không gửi được: giải phóng tài xế đang được mời.
     * Script tự ZREM mục timeout nên người gọi không cần xoá trước.
     *
     * KEYS[1] = ride:{id}:state        (HASH)
     * KEYS[2] = dispatch:timeouts      (ZSET)
     * KEYS[3] = ride:{id}:rejected     (SET)
     * KEYS[4] = available_drivers      (GEO)
     * ARGV[1] = driverId
     * ARGV[2] = rideId
     * ARGV[3] = tên bộ đếm trong driver:{id}:stats: "rejected", "timeout" hoặc "unreachable"
     * ARGV[4] = thời gian sống của tập rejected (giây), lấy từ RedisTtl.RIDE_STATE
     *
     * Trả 1 nếu đúng là lời mời hiện tại (được mời người kế), 0 nếu lời mời cũ hoặc đã nhận rồi.
     */
    val RELEASE = DefaultRedisScript<Long>(
        """
        if redis.call('HGET', KEYS[1], 'status') ~= 'SEARCHING'
           or redis.call('HGET', KEYS[1], 'currentDriver') ~= ARGV[1]
           or redis.call('HGET', KEYS[1], 'currentOfferId') ~= ARGV[5] then
            return 0
        end

        redis.call('ZREM', KEYS[2], ARGV[2] .. ':' .. ARGV[1] .. ':' .. ARGV[5])
        redis.call('DEL', 'ride:' .. ARGV[2] .. ':current_offer')
        redis.call('HDEL', KEYS[1], 'currentDriver', 'currentOfferId', 'offerExpiresAt')
        redis.call('SADD', KEYS[3], ARGV[1])
        redis.call('EXPIRE', KEYS[3], ARGV[4])
        if redis.call('GET', 'driver:' .. ARGV[1] .. ':lock') == ARGV[2] then redis.call('DEL', 'driver:' .. ARGV[1] .. ':lock') end
        redis.call('HINCRBY', 'driver:' .. ARGV[1] .. ':stats', ARGV[3], 1)

        if redis.call('EXISTS', 'driver:' .. ARGV[1] .. ':current_ride') == 1 or redis.call('EXISTS', 'driver:' .. ARGV[1] .. ':lock') == 1 then return 1 end
        if redis.call('EXISTS', 'driver:alive:' .. ARGV[1]) == 1 and redis.call('EXISTS', 'driver:' .. ARGV[1] .. ':current_ride') == 0 and redis.call('EXISTS', 'driver:' .. ARGV[1] .. ':lock') == 0 then
            redis.call('SET', 'driver:' .. ARGV[1] .. ':status', 'ONLINE')
            local lat = redis.call('HGET', 'driver:' .. ARGV[1] .. ':location', 'lat')
            local lng = redis.call('HGET', 'driver:' .. ARGV[1] .. ':location', 'lng')
            if lat and lng then
                redis.call('GEOADD', KEYS[4], lng, lat, ARGV[1])
            end
        else
            redis.call('SET', 'driver:' .. ARGV[1] .. ':status', 'OFFLINE')
        end

        return 1
        """.trimIndent(),
        Long::class.java
    )

    /**
     * Khách huỷ khi chuyến còn SEARCHING. Nếu đang có lời mời treo thì thu hồi luôn.
     *
     * KEYS[1] = ride:{id}:state        (HASH)
     * KEYS[2] = dispatch:timeouts      (ZSET)
     * KEYS[3] = ride:{id}:candidates   (LIST)
     * KEYS[4] = available_drivers      (GEO)
     * ARGV[1] = rideId
     *
     * Trả: nil nếu chuyến không còn SEARCHING (không huỷ được bằng script này),
     *      "" nếu đã huỷ và không có tài xế đang bị mời,
     *      driverId nếu đã huỷ và tài xế đó vừa bị thu hồi lời mời (cần gửi OFFER_CANCELLED).
     */
    val CANCEL_SEARCH = DefaultRedisScript<String>(
        """
        if redis.call('HGET', KEYS[1], 'status') ~= 'SEARCHING' then
            return false
        end

        redis.call('HSET', KEYS[1], 'status', 'CANCELLED')
        redis.call('DEL', KEYS[3])

        local d = redis.call('HGET', KEYS[1], 'currentDriver')
        if not d then
            return ''
        end

        local token = redis.call('HGET', KEYS[1], 'currentOfferId')
        redis.call('HDEL', KEYS[1], 'currentDriver', 'currentOfferId', 'offerExpiresAt')
        redis.call('DEL', 'ride:' .. ARGV[1] .. ':current_offer')
        if token then redis.call('ZREM', KEYS[2], ARGV[1] .. ':' .. d .. ':' .. token) end
        if redis.call('GET', 'driver:' .. d .. ':lock') == ARGV[1] then redis.call('DEL', 'driver:' .. d .. ':lock') end

        if redis.call('EXISTS', 'driver:' .. d .. ':current_ride') == 1 or redis.call('EXISTS', 'driver:' .. d .. ':lock') == 1 then return d end
        if redis.call('EXISTS', 'driver:alive:' .. d) == 1 and redis.call('EXISTS', 'driver:' .. d .. ':current_ride') == 0 and redis.call('EXISTS', 'driver:' .. d .. ':lock') == 0 then
            redis.call('SET', 'driver:' .. d .. ':status', 'ONLINE')
            local lat = redis.call('HGET', 'driver:' .. d .. ':location', 'lat')
            local lng = redis.call('HGET', 'driver:' .. d .. ':location', 'lng')
            if lat and lng then
                redis.call('GEOADD', KEYS[4], lng, lat, d)
            end
        else
            redis.call('SET', 'driver:' .. d .. ':status', 'OFFLINE')
        end

        return d
        """.trimIndent(),
        String::class.java
    )
    val UNLOCK_TICK = DefaultRedisScript<Long>(
        "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
        Long::class.java,
    )

    val RECONCILE_IDLE = DefaultRedisScript<Long>(
        """
        local d = ARGV[1]
        if redis.call('EXISTS', 'driver:' .. d .. ':lock') == 1 then return 0 end
        local current = redis.call('GET', 'driver:' .. d .. ':current_ride') or ''
        if current ~= ARGV[2] then return 0 end
        redis.call('DEL', 'driver:' .. d .. ':current_ride')
        if redis.call('EXISTS', 'driver:alive:' .. d) == 1 then
            redis.call('SET', 'driver:' .. d .. ':status', 'ONLINE')
            local lat = redis.call('HGET', 'driver:' .. d .. ':location', 'lat')
            local lng = redis.call('HGET', 'driver:' .. d .. ':location', 'lng')
            if lat and lng then redis.call('GEOADD', KEYS[1], lng, lat, d) end
        else
            redis.call('SET', 'driver:' .. d .. ':status', 'OFFLINE')
            redis.call('ZREM', KEYS[1], d)
        end
        return 1
        """.trimIndent(), Long::class.java,
    )

    val ADD_AVAILABLE = DefaultRedisScript<Long>(
        """
        local d = ARGV[1]
        if redis.call('GET', 'driver:' .. d .. ':status') ~= 'ONLINE'
           or redis.call('EXISTS', 'driver:' .. d .. ':lock') == 1
           or redis.call('EXISTS', 'driver:' .. d .. ':current_ride') == 1
           or redis.call('EXISTS', 'driver:alive:' .. d) == 0 then
            redis.call('ZREM', KEYS[1], d)
            return 0
        end
        if ARGV[4] and ARGV[4] ~= '' and
           redis.call('HGET', 'driver:' .. d .. ':location', 'sampledAtMs') ~= ARGV[4] then return 0 end
        redis.call('GEOADD', KEYS[1], ARGV[2], ARGV[3], d)
        return 1
        """.trimIndent(), Long::class.java,
    )

}
