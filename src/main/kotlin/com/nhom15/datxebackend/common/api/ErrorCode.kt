package com.nhom15.datxebackend.common.api

import org.springframework.http.HttpStatus

enum class ErrorCode(val httpStatus: HttpStatus, val defaultMessage: String) {

    // ---- Chung ----
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Lỗi hệ thống, vui lòng thử lại sau"),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "Yêu cầu không hợp lệ"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Chưa đăng nhập hoặc phiên đã hết hạn"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Bạn không có quyền thực hiện thao tác này"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy dữ liệu"),
    CONFLICT(HttpStatus.CONFLICT, "Dữ liệu vừa bị thay đổi, vui lòng thử lại"),

    // ---- Auth & User ----
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"),
    PHONE_ALREADY_EXISTS(HttpStatus.CONFLICT, "Số điện thoại đã được đăng ký"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Số điện thoại hoặc mật khẩu không đúng"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Token đã hết hạn"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Token không hợp lệ"),
    FIREBASE_AUTH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Dịch vụ xác minh tài khoản tạm thời không khả dụng"),
    OTP_INVALID(HttpStatus.BAD_REQUEST, "Mã OTP không đúng"),
    OTP_EXPIRED(HttpStatus.BAD_REQUEST, "Mã OTP đã hết hạn"),
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "Tài khoản đã bị khóa"),
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "Tài khoản chưa được đăng ký"),
    DRIVER_NOT_VERIFIED(HttpStatus.FORBIDDEN, "Tài xế chưa được duyệt"),
    DRIVER_DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy giấy tờ hiện tại của tài xế"),
    DRIVER_SKILL_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy kỹ năng tài xế"),
    VEHICLE_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy xe"),
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy thông báo"),
    ACCOUNT_ALREADY_EXISTS(HttpStatus.CONFLICT, "Tài khoản đã được đăng ký"),
    ACCOUNT_HAS_ACTIVE_RIDES(HttpStatus.CONFLICT, "Hãy hoàn tất hoặc hủy các chuyến chưa kết thúc trước khi xóa tài khoản"),
    PHONE_MISMATCH(HttpStatus.BAD_REQUEST, "Số điện thoại không khớp với xác thực Firebase"),

    // ---- Ride ----
    RIDE_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy chuyến đi"),
    INVALID_RIDE_STATE(HttpStatus.CONFLICT, "Trạng thái chuyến đi không cho phép thao tác này"),
    RIDE_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "Chuyến đã có tài xế nhận"),
    RIDE_ALREADY_ACTIVE(HttpStatus.CONFLICT, "Bạn đang có một chuyến chưa hoàn tất"),
    RIDE_CANNOT_CANCEL(HttpStatus.CONFLICT, "Không thể hủy chuyến ở trạng thái hiện tại"),
    HANDOVER_REPORT_REQUIRED(HttpStatus.BAD_REQUEST, "Cần hoàn tất biên bản bàn giao xe"),
    PRICING_RULE_NOT_FOUND(HttpStatus.NOT_FOUND, "Chưa có bảng giá áp dụng"),
    ROUTE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Không thể lấy lộ trình chuyến đi"),

    // ---- Matching ----
    OFFER_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy lời mời nhận chuyến"),
    OFFER_EXPIRED(HttpStatus.CONFLICT, "Lời mời nhận chuyến đã hết hạn"),
    DRIVER_NOT_AVAILABLE(HttpStatus.CONFLICT, "Tài xế không sẵn sàng nhận chuyến"),

    // ---- Realtime ----
    LOCATION_UNAVAILABLE(HttpStatus.NOT_FOUND, "Chưa có vị trí của tài xế"),
    CHAT_NOT_AVAILABLE(HttpStatus.CONFLICT, "Hội thoại của chuyến này chưa khả dụng"),
    CHAT_CLOSED(HttpStatus.CONFLICT, "Hội thoại của chuyến đã đóng"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Bạn gửi yêu cầu quá nhanh, vui lòng thử lại sau"),

    // ---- Payment & Rating ----
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy giao dịch thanh toán"),
    PAYMENT_ALREADY_PAID(HttpStatus.CONFLICT, "Chuyến đã được thanh toán"),
    INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "Số dư ví không đủ"),
    INVALID_SIGNATURE(HttpStatus.BAD_REQUEST, "Chữ ký callback không hợp lệ"),
    VOUCHER_INVALID(HttpStatus.BAD_REQUEST, "Mã giảm giá không hợp lệ hoặc đã hết hạn"),
    RATING_ALREADY_EXISTS(HttpStatus.CONFLICT, "Bạn đã đánh giá chuyến này rồi")
}
