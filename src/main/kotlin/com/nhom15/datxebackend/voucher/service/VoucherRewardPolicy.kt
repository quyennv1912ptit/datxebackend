package com.nhom15.datxebackend.voucher.service

data class VoucherTemplate(
    val codePrefix: String,
    val discountType: String,
    val value: Long,
    val maxDiscount: Long?,
    val minimumOrderAmount: Long,
    val validDays: Long,
)

object VoucherRewardPolicy {
    const val SOURCE_SYSTEM_REWARD = "SYSTEM_REWARD"
    val WELCOME = VoucherTemplate("WELCOME", "PERCENT", 20, 30_000, 0, 30)

    private val MILESTONES = mapOf(
        5L to VoucherTemplate("GO5", "FIXED", 10_000, 10_000, 0, 30),
        10L to VoucherTemplate("GO10", "PERCENT", 15, 25_000, 0, 30),
        20L to VoucherTemplate("GO20", "PERCENT", 20, 40_000, 0, 30),
    )

    fun milestoneReward(completedRides: Long): VoucherTemplate? = MILESTONES[completedRides]

    /** Xét mốc từ cao xuống thấp để có thể tự phục hồi mốc bị bỏ sót ở chuyến 6/11/21. */
    fun milestonesDesc(): List<Long> = MILESTONES.keys.sortedDescending()
}
