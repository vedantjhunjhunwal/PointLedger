package dev.pointledger.engine;

import java.util.List;

/** Value types shared by the engine and the HTTP layer. Prices are whole yen; points are whole points. */
public final class Model {
    private Model() {}

    /**
     * A seller-funded points campaign: {@code rateBp} basis points of the tax-exclusive price of
     * that seller's items (500 = 5%), capped per order, paid out of a fixed budget the seller
     * deposits up front. Campaigns sharing a non-empty {@code group} cannot stack on one order.
     */
    public record Campaign(String id, String sellerId, int rateBp, String group, long perOrderCap,
                           long budget, long minSubtotal, long startsAt, long endsAt) {
        public Campaign {
            if (rateBp <= 0 || rateBp > 10_000) throw new IllegalArgumentException("rateBp must be 1..10000");
            if (perOrderCap <= 0 || budget <= 0) throw new IllegalArgumentException("perOrderCap and budget must be > 0");
            if (minSubtotal < 0) throw new IllegalArgumentException("minSubtotal must be >= 0");
            if (endsAt != 0 && endsAt <= startsAt) throw new IllegalArgumentException("endsAt must be after startsAt");
        }

        public boolean activeAt(long now) {
            return now >= startsAt && (endsAt == 0 || now < endsAt);
        }
    }

    public record Line(String sku, String sellerId, long unitPriceInclTax, int qty, int taxRatePct) {
        public Line {
            if (unitPriceInclTax < 0 || qty <= 0) throw new IllegalArgumentException("bad line " + sku);
            if (taxRatePct != 8 && taxRatePct != 10 && taxRatePct != 0)
                throw new IllegalArgumentException("taxRatePct must be 0, 8 or 10");
        }

        /** Tax-exclusive value, rounded down to the yen (points are earned on the pre-tax price). */
        public long taxExclusive() {
            return Math.floorDiv(unitPriceInclTax * qty * 100, 100 + taxRatePct);
        }
    }

    public record OrderRequest(String orderId, String customerId, List<Line> lines) {
        public OrderRequest {
            if (lines == null || lines.isEmpty()) throw new IllegalArgumentException("order has no lines");
            lines = List.copyOf(lines);
        }
    }

    public enum OrderStatus { PLACED, SHIPPED, CANCELLED }

    public record Award(String campaignId, long points) {}

    public record OrderView(String orderId, String customerId, OrderStatus status, long taxExclusiveTotal,
                            long orderCap, List<Award> awards, long totalPoints, long greedyPoints) {}

    public record LotView(String orderId, long remaining, long expiresAt) {}

    public record CustomerView(String customerId, long balance, List<LotView> lots) {}

    public record CampaignView(Campaign campaign, long available, long reserved, long awarded,
                               long refunded, boolean closed) {}

    public static final class ConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public ConflictException(String m) { super(m); }
    }

    public static final class NotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public NotFoundException(String m) { super(m); }
    }
}
