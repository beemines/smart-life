package com.bee.smartlife.checkout;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Integer-cent arithmetic; callers validate ownership, state and time against their database clock. */
public final class CheckoutCalculator {
    private CheckoutCalculator() { }

    public record Rule(long id, String kind, long threshold, long reduction, int payableBps,
                       long cap, int priority, Instant expiresAt, boolean stackMerchant,
                       boolean stackVoucher, boolean stackPlatform) { }

    public record PaidVoucher(long id, long price, long face, long threshold,
                              boolean stackMerchant, boolean stackPlatform) { }

    public record Calculation(long total, long excluded, long eligible, long merchantDiscount,
                              long voucherDeduction, long unusedVoucherValue, long platformDiscount,
                              long payable, long previousPaid, long totalCash, long cashSaving,
                              Long merchantId, Long voucherId, Long platformId) { }

    public static Calculation calculate(long total, long excluded, Rule merchant,
                                        PaidVoucher voucher, Rule platform) {
        validateAmounts(total, excluded);
        validateRule(merchant);
        validateRule(platform);
        validateVoucher(voucher);
        if (merchant != null && voucher != null && (!merchant.stackVoucher() || !voucher.stackMerchant()))
            throw new IllegalArgumentException("门店优惠与代金券不允许叠加");
        if (merchant != null && platform != null && (!merchant.stackPlatform() || !platform.stackMerchant()))
            throw new IllegalArgumentException("门店优惠与平台券不允许叠加");
        if (voucher != null && platform != null && (!voucher.stackPlatform() || !platform.stackVoucher()))
            throw new IllegalArgumentException("代金券与平台券不允许叠加");
        try {
            long eligible = Math.subtractExact(total, excluded);
            long merchantDiscount = discount(merchant, eligible);
            long afterMerchant = Math.subtractExact(eligible, merchantDiscount);
            long deduction = 0;
            long previousPaid = 0;
            long unusedValue = 0;
            if (voucher != null) {
                if (afterMerchant < voucher.threshold())
                    throw new IllegalArgumentException("未达到代金券最低消费门槛");
                deduction = Math.min(afterMerchant, voucher.face());
                if (deduction == 0) throw new IllegalArgumentException("代金券本次抵扣为零");
                previousPaid = voucher.price();
                unusedValue = Math.subtractExact(voucher.face(), deduction);
            }
            long afterVoucher = Math.subtractExact(afterMerchant, deduction);
            long platformDiscount = discount(platform, afterVoucher);
            if (platform != null) {
                // Excluded consumption already contributes to the required one-cent payment.
                long maximum = excluded == 0 ? Math.max(0, afterVoucher - 1) : afterVoucher;
                platformDiscount = Math.min(platformDiscount, maximum);
                if (platformDiscount == 0) throw new IllegalArgumentException("平台券本次优惠为零");
            }
            long payable = Math.addExact(excluded, Math.subtractExact(afterVoucher, platformDiscount));
            long totalCash = Math.addExact(previousPaid, payable);
            return new Calculation(total, excluded, eligible, merchantDiscount, deduction, unusedValue,
                    platformDiscount, payable, previousPaid, totalCash, Math.subtractExact(total, totalCash),
                    merchant == null ? null : merchant.id(), voucher == null ? null : voucher.id(),
                    platform == null ? null : platform.id());
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("金额计算溢出", overflow);
        }
    }

    /** Enumerates at most 21 * 21 free combinations while keeping the customer's voucher fixed. */
    public static Calculation best(long total, long excluded, List<Rule> merchants,
                                   PaidVoucher voucher, List<Rule> platforms) {
        validateAmounts(total, excluded);
        validateVoucher(voucher);
        List<Rule> merchantChoices = choices(merchants);
        List<Rule> platformChoices = choices(platforms);
        Candidate winner = null;
        for (Rule merchant : merchantChoices) {
            for (Rule platform : platformChoices) {
                Calculation calculation;
                try {
                    calculation = calculate(total, excluded, merchant, voucher, platform);
                } catch (IllegalArgumentException unavailable) {
                    continue;
                }
                Candidate candidate = new Candidate(calculation, merchant, platform);
                if (winner == null || CANDIDATE_ORDER.compare(candidate, winner) < 0) winner = candidate;
            }
        }
        if (winner == null) throw new IllegalArgumentException("所选代金券没有可用的优惠组合");
        return winner.calculation();
    }

    private static void validateAmounts(long total, long excluded) {
        if (total < 1 || total > 100_000_000 || excluded < 0 || excluded > total)
            throw new IllegalArgumentException("消费总额或不参与优惠金额无效");
    }

    private static void validateRule(Rule rule) {
        if (rule == null) return;
        if (rule.threshold() < 0 || rule.cap() < 0)
            throw new IllegalArgumentException("优惠门槛与封顶金额不能为负数");
        if ("FULL_REDUCTION".equals(rule.kind())) {
            if (rule.reduction() <= 0 || rule.payableBps() != 0)
                throw new IllegalArgumentException("满减金额或规则类型字段无效");
        } else if ("DISCOUNT".equals(rule.kind())) {
            if (rule.reduction() != 0 || rule.payableBps() < 1 || rule.payableBps() > 9999)
                throw new IllegalArgumentException("折扣基点或规则类型字段无效");
        } else throw new IllegalArgumentException("未知优惠类型");
    }

    private static void validateVoucher(PaidVoucher voucher) {
        if (voucher != null && (voucher.price() < 0 || voucher.face() <= 0 || voucher.threshold() < 0))
            throw new IllegalArgumentException("代金券金额或门槛无效");
    }

    private static long discount(Rule rule, long base) {
        if (rule == null) return 0;
        if (base < rule.threshold()) throw new IllegalArgumentException("未达到优惠门槛");
        long amount = "FULL_REDUCTION".equals(rule.kind()) ? rule.reduction()
                : Math.multiplyExact(base, 10000L - rule.payableBps()) / 10000;
        if ("DISCOUNT".equals(rule.kind()) && rule.cap() > 0) amount = Math.min(amount, rule.cap());
        amount = Math.min(base, amount);
        if (amount == 0) throw new IllegalArgumentException("本次优惠为零");
        return amount;
    }

    private static List<Rule> choices(List<Rule> rules) {
        if (rules == null || rules.size() > 20 || rules.stream().anyMatch(rule -> rule == null))
            throw new IllegalArgumentException("优惠候选列表无效或超过20项");
        List<Rule> result = new ArrayList<>(rules.size() + 1);
        result.add(null);
        result.addAll(rules);
        return result;
    }

    private record Candidate(Calculation calculation, Rule merchant, Rule platform) { }

    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
            .comparingLong((Candidate c) -> c.calculation().payable())
            .thenComparing(Comparator.comparingInt((Candidate c) -> priority(c.merchant())).reversed())
            .thenComparing(Comparator.comparingInt((Candidate c) -> priority(c.platform())).reversed())
            .thenComparing(c -> c.platform() == null ? null : c.platform().expiresAt(),
                    Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(c -> c.merchant() == null ? null : c.merchant().id(),
                    Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(c -> c.platform() == null ? null : c.platform().id(),
                    Comparator.nullsLast(Comparator.naturalOrder()));

    private static int priority(Rule rule) { return rule == null ? 0 : rule.priority(); }
}
