package com.cloudkitchen.domain.contract;

import com.cloudkitchen.domain.PaymentResult;
import java.math.BigDecimal;

/** Something that can be paid for: it knows its amount and how the payment settles. */
public interface Payable {

    /** What the customer owes for this order, in rupees with two decimals. */
    BigDecimal getPayableAmount();

    /** Computes how the payment settles for this kind of order. It has no side effects and can be called repeatedly. */
    PaymentResult processPayment();
}
