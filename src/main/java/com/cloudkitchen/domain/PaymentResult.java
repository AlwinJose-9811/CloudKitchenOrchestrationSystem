package com.cloudkitchen.domain;

import com.cloudkitchen.domain.enums.PaymentStatus;
import com.cloudkitchen.util.MoneyUtil;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * How an order's payment settles. {@code amountCharged} is what the customer is charged for the order and
 * {@code status} says whether that charge is already settled; {@code netToKitchen} is the charge minus the platform
 * fee. The constructor keeps the three amounts consistent.
 */
public record PaymentResult(PaymentStatus status, BigDecimal amountCharged, BigDecimal platformFee,
                            BigDecimal netToKitchen) {

    public PaymentResult {
        Objects.requireNonNull(status, "status");
        amountCharged = requireMoney(amountCharged, "amountCharged");
        platformFee = requireMoney(platformFee, "platformFee");
        netToKitchen = requireMoney(netToKitchen, "netToKitchen");
        if (platformFee.compareTo(amountCharged) > 0) {
            throw new IllegalArgumentException("platform fee exceeds the amount charged");
        }
        if (amountCharged.subtract(platformFee).compareTo(netToKitchen) != 0) {
            throw new IllegalArgumentException("netToKitchen must equal amountCharged minus platformFee");
        }
    }

    private static BigDecimal requireMoney(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (!MoneyUtil.fitsTwoDecimals(value) || value.signum() < 0) {
            throw new IllegalArgumentException(name + " must be a non-negative amount with at most two decimals");
        }
        return MoneyUtil.scale(value);
    }
}
