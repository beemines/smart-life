package com.bee.smartlife.checkout;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static com.bee.smartlife.checkout.CheckoutCalculator.*;
import static org.junit.jupiter.api.Assertions.*;

class CheckoutCalculatorTest {
    private static final Instant EARLY = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant LATE = Instant.parse("2031-01-01T00:00:00Z");

    private Rule reduction(long id, long threshold, long reduction) {
        return new Rule(id, "FULL_REDUCTION", threshold, reduction, 0, 0, 0, LATE, true, true, true);
    }

    private Rule priority(long id, int priority, Instant expiry) {
        return new Rule(id, "FULL_REDUCTION", 0, 100, 0, 0, priority, expiry, true, true, true);
    }

    private PaidVoucher voucher() { return new PaidVoucher(7, 8000, 10000, 0, true, true); }

    @Test void paidVoucherShowsPriorCostAndCurrentPaymentSeparately() {
        assertEquals(new Calculation(18000, 2000, 16000, 0, 10000, 0, 0, 8000,
                8000, 16000, 2000, null, 7L, null), calculate(18000, 2000, null, voucher(), null));
    }

    @Test void merchantDiscountAppliesOnlyToEligibleAmountBeforeVoucher() {
        assertEquals(16000, calculate(18000, 2000, reduction(1, 15000, 2000), null, null).payable());
        Calculation c = calculate(18000, 2000, reduction(1, 15000, 2000), voucher(), null);
        assertEquals(2000, c.merchantDiscount());
        assertEquals(6000, c.payable());
        assertEquals(14000, c.totalCash());
        assertEquals(4000, c.cashSaving());
    }

    @Test void platformThresholdUsesRemainderAfterBothEarlierSteps() {
        assertThrows(IllegalArgumentException.class, () -> calculate(18000, 2000,
                reduction(1, 15000, 2000), voucher(), reduction(2, 5000, 1000)));
        assertNull(best(18000, 2000, List.of(reduction(1, 15000, 2000)), voucher(),
                List.of(reduction(2, 5000, 1000))).platformId());
    }

    @Test void percentageDiscountRoundsSavingsDownAndHonorsCap() {
        Rule nineTenths = new Rule(1, "DISCOUNT", 0, 0, 9000, 0, 0, LATE, true, true, true);
        assertEquals(10, calculate(101, 0, nineTenths, null, null).merchantDiscount());
        assertEquals(91, calculate(101, 0, nineTenths, null, null).payable());
        Rule capped = new Rule(1, "DISCOUNT", 0, 0, 1, 3, 0, LATE, true, true, true);
        assertEquals(98, calculate(101, 0, capped, null, null).payable());
        assertEquals(1, calculate(10000, 0, new Rule(1, "DISCOUNT", 0, 0, 1, 0, 0, LATE,
                true, true, true), null, null).payable());
        assertEquals(9999, calculate(10000, 0, new Rule(1, "DISCOUNT", 0, 0, 9999, 0, 0, LATE,
                true, true, true), null, null).payable());
    }

    @Test void platformLeavesOneCentUnlessExcludedAmountAlreadyCoversIt() {
        assertEquals(1, calculate(100, 0, null, null, reduction(2, 0, 100)).payable());
        assertEquals(99, calculate(100, 0, null, null, reduction(2, 0, 100)).platformDiscount());
        assertEquals(20, calculate(100, 20, null, null, reduction(2, 0, 100)).payable());
        assertEquals(0, calculate(100, 0, reduction(1, 0, 1000), null, null).payable());
    }

    @Test void voucherOverFaceDoesNotReturnChangeAndCashSavingMayBeNegative() {
        Calculation c = calculate(3000, 0, null, voucher(), null);
        assertEquals(7000, c.unusedVoucherValue());
        assertEquals(3000, c.voucherDeduction());
        assertEquals(0, c.payable());
        assertEquals(-5000, c.cashSaving());
    }

    @Test void voucherThresholdIsCheckedAfterMerchantDiscount() {
        PaidVoucher threshold = new PaidVoucher(7, 100, 100, 15000, true, true);
        assertThrows(IllegalArgumentException.class, () -> calculate(18000, 2000,
                reduction(1, 0, 2000), threshold, null));
        assertEquals(17900, best(18000, 2000, List.of(reduction(1, 0, 2000)), threshold, List.of()).payable());
    }

    @Test void allExcludedAllowsOnlyOriginalPriceAndNoEntitlementConsumption() {
        assertEquals(100, calculate(100, 100, null, null, null).payable());
        assertEquals(calculate(100, 100, null, null, null),
                best(100, 100, List.of(reduction(1, 0, 100)), null, List.of(reduction(2, 0, 100))));
        assertThrows(IllegalArgumentException.class, () -> calculate(100, 100, null, voucher(), null));
        assertThrows(IllegalArgumentException.class, () -> best(100, 100, List.of(), voucher(), List.of()));
    }

    @Test void zeroBenefitRulesAndPlatformOnOneCentAreRejected() {
        Rule tiny = new Rule(1, "DISCOUNT", 0, 0, 9999, 0, 0, LATE, true, true, true);
        assertThrows(IllegalArgumentException.class, () -> calculate(1, 0, tiny, null, null));
        assertThrows(IllegalArgumentException.class, () -> calculate(1, 0, null, null, reduction(2, 0, 1)));
        assertThrows(IllegalArgumentException.class, () -> calculate(100, 0, reduction(1, 0, 100), voucher(), null));
        assertNull(best(1, 0, List.of(tiny), null, List.of(reduction(2, 0, 1))).platformId());
    }

    @Test void amountValidationRejectsNegativeOutOfRangeAndOverflow() {
        for (long total : new long[] { -1, 0, 100000001, Long.MAX_VALUE })
            assertThrows(IllegalArgumentException.class, () -> calculate(total, 0, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> calculate(100, -1, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> calculate(100, 101, null, null, null));
        assertEquals(100000000, calculate(100000000, 0, null, null, null).payable());
        assertThrows(IllegalArgumentException.class, () -> calculate(100, 0, null,
                new PaidVoucher(7, Long.MAX_VALUE, 1, 0, true, true), null));
    }

    @Test void typedRuleValidationRejectsMalformedNumbersAndKinds() {
        List<Rule> invalid = List.of(
                new Rule(1, "OTHER", 0, 1, 0, 0, 0, LATE, true, true, true),
                new Rule(1, null, 0, 1, 0, 0, 0, LATE, true, true, true),
                reduction(1, -1, 1), reduction(1, 0, -1), reduction(1, 0, 0),
                new Rule(1, "DISCOUNT", 0, 0, 0, 0, 0, LATE, true, true, true),
                new Rule(1, "DISCOUNT", 0, 0, 10000, 0, 0, LATE, true, true, true),
                new Rule(1, "DISCOUNT", 0, 1, 9000, 0, 0, LATE, true, true, true),
                new Rule(1, "FULL_REDUCTION", 0, 1, 9000, 0, 0, LATE, true, true, true),
                new Rule(1, "DISCOUNT", 0, 0, 9000, -1, 0, LATE, true, true, true));
        for (Rule rule : invalid) assertThrows(IllegalArgumentException.class,
                () -> calculate(100, 0, rule, null, null), String.valueOf(rule));
        for (PaidVoucher v : List.of(new PaidVoucher(7, -1, 10, 0, true, true),
                new PaidVoucher(7, 0, 0, 0, true, true), new PaidVoucher(7, 0, 10, -1, true, true)))
            assertThrows(IllegalArgumentException.class, () -> calculate(100, 0, null, v, null));
    }

    @Test void stackingRequiresBothSidesConsentForEachPair() {
        Rule m = reduction(1, 0, 10), p = reduction(2, 0, 10);
        PaidVoucher v = new PaidVoucher(7, 1, 10, 0, true, true);
        List<Runnable> invalid = List.of(
                () -> calculate(100, 0, new Rule(1, "FULL_REDUCTION", 0, 10, 0, 0, 0, LATE, true, false, true), v, null),
                () -> calculate(100, 0, m, new PaidVoucher(7, 1, 10, 0, false, true), null),
                () -> calculate(100, 0, new Rule(1, "FULL_REDUCTION", 0, 10, 0, 0, 0, LATE, true, true, false), null, p),
                () -> calculate(100, 0, m, null, new Rule(2, "FULL_REDUCTION", 0, 10, 0, 0, 0, LATE, false, true, true)),
                () -> calculate(100, 0, null, new PaidVoucher(7, 1, 10, 0, true, false), p),
                () -> calculate(100, 0, null, v, new Rule(2, "FULL_REDUCTION", 0, 10, 0, 0, 0, LATE, true, false, true)));
        for (Runnable action : invalid) assertThrows(IllegalArgumentException.class, action::run);
        assertEquals(70, calculate(100, 0, m, v, p).payable());
    }

    @Test void automaticBestEnumeratesBothFreeChoicesAndKeepsVoucherFixed() {
        List<Rule> m = new ArrayList<>(), p = new ArrayList<>();
        for (int i = 1; i <= 20; i++) { m.add(reduction(i, 0, i)); p.add(reduction(100 + i, 0, i)); }
        Calculation c = best(100, 0, m, new PaidVoucher(7, 1, 10, 0, true, true), p);
        assertEquals(50, c.payable());
        assertEquals(20L, c.merchantId());
        assertEquals(120L, c.platformId());
        assertEquals(7L, c.voucherId());
        m.add(reduction(21, 0, 21));
        assertThrows(IllegalArgumentException.class, () -> best(100, 0, m, null, p));
        assertThrows(IllegalArgumentException.class, () -> best(100, 0, p, null, m));
    }

    @Test void bestTieOrderUsesPrioritiesThenExpiryThenIdsIndependentlyOfInputOrder() {
        List<Rule> m = new ArrayList<>(List.of(priority(3, 1, EARLY), priority(2, 2, LATE), priority(1, 2, EARLY)));
        List<Rule> p = new ArrayList<>(List.of(priority(13, 1, EARLY), priority(12, 2, LATE),
                priority(11, 2, EARLY), priority(10, 2, EARLY)));
        Calculation expected = best(1000, 0, m, null, p);
        assertEquals(1L, expected.merchantId());
        assertEquals(10L, expected.platformId());
        for (int i = 0; i < 10; i++) {
            Collections.rotate(m, 1); Collections.rotate(p, 1);
            assertEquals(expected, best(1000, 0, m, null, p));
        }
    }

    @Test void equalPayablePrefersHigherMerchantPriorityEvenWhenOnlyPlatformCouldPaySame() {
        Calculation c = best(1000, 0, List.of(priority(1, 1, LATE)), null,
                List.of(new Rule(2, "FULL_REDUCTION", 0, 100, 0, 0, 2, EARLY, false, true, true)));
        assertEquals(1L, c.merchantId());
        assertNull(c.platformId());
        Calculation negativePriority = best(1000, 0, List.of(priority(1, -1, LATE)), null,
                List.of(new Rule(2, "FULL_REDUCTION", 0, 100, 0, 0, 0, EARLY, false, true, true)));
        assertNull(negativePriority.merchantId());
        assertEquals(2L, negativePriority.platformId());
    }
}
