package com.nhom15.datxebackend.payment.exception

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.AppException

class InsufficientCustomerBalanceException(message: String = ErrorCode.INSUFFICIENT_CUSTOMER_BALANCE.defaultMessage) :
    AppException(ErrorCode.INSUFFICIENT_CUSTOMER_BALANCE, message)

class CreditWalletBelowMinimumException(message: String = ErrorCode.CREDIT_WALLET_BELOW_MINIMUM.defaultMessage) :
    AppException(ErrorCode.CREDIT_WALLET_BELOW_MINIMUM, message)

class UserNotFoundException(message: String = ErrorCode.USER_NOT_FOUND.defaultMessage) :
    AppException(ErrorCode.USER_NOT_FOUND, message)

class WalletNotFoundException(message: String = ErrorCode.WALLET_NOT_FOUND.defaultMessage) :
    AppException(ErrorCode.WALLET_NOT_FOUND, message)

class InvalidTripStateException(message: String = ErrorCode.INVALID_RIDE_STATE.defaultMessage) :
    AppException(ErrorCode.INVALID_RIDE_STATE, message)

class InsufficientCreditBalanceException(message: String = ErrorCode.INSUFFICIENT_CREDIT_BALANCE.defaultMessage) :
    AppException(ErrorCode.INSUFFICIENT_CREDIT_BALANCE, message)

class PaymentMethodMismatchException(message: String = ErrorCode.PAYMENT_METHOD_MISMATCH.defaultMessage) :
    AppException(ErrorCode.PAYMENT_METHOD_MISMATCH, message)

class InvalidAmountException(message: String = ErrorCode.INVALID_AMOUNT.defaultMessage) :
    AppException(ErrorCode.INVALID_AMOUNT, message)

class InsufficientCashBalanceException(message: String = ErrorCode.INSUFFICIENT_CASH_BALANCE.defaultMessage) :
    AppException(ErrorCode.INSUFFICIENT_CASH_BALANCE, message)

class InvalidWalletTypeException(message: String = ErrorCode.INVALID_WALLET_TYPE.defaultMessage) :
    AppException(ErrorCode.INVALID_WALLET_TYPE, message)

class WithdrawalRequestNotFoundException(message: String = ErrorCode.WITHDRAWAL_REQUEST_NOT_FOUND.defaultMessage) :
    AppException(ErrorCode.WITHDRAWAL_REQUEST_NOT_FOUND, message)

class InvalidWithdrawalActionException(message: String = ErrorCode.INVALID_WITHDRAWAL_ACTION.defaultMessage) :
    AppException(ErrorCode.INVALID_WITHDRAWAL_ACTION, message)

class InvalidTopUpAmountException(message: String = ErrorCode.INVALID_TOP_UP_AMOUNT.defaultMessage) :
    AppException(ErrorCode.INVALID_TOP_UP_AMOUNT, message)

