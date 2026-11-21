package dev.pointledger.engine;

import dev.pointledger.engine.Model.*;
import dev.pointledger.ledger.InsufficientFundsException;
import dev.pointledger.ledger.Ledger;
import dev.pointledger.promo.StackingOptimizer;
import dev.pointledger.promo.StackingOptimizer.Candidate;

import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * Business rules for seller-funded loyalty points, built on the {@link Ledger}.
 *
 * <p>Money-like flow of points (every arrow is one balanced ledger transfer):
 * <pre>
 *   seller:S --FUND--> campaign:C --RESERVE--> hold:O --AWARD--> customer:U --REDEEM--> system:redeemed
 *                          ^                     |                    |
 *                          +------RELEASE--------+                    +--EXPIRE--> system:breakage
 *   campaign:C --REFUND--> seller:S   (when a campaign is closed)
 * </pre>
 * Points are reserved from the campaign when the order is placed, so a campaign's budget can
 * never be oversold even under heavy concurrency, and they reach the customer only when the order
 * ships. Every public method runs inside {@link Ledger#atomically}, so each business operation
 * is all-or-nothing and the invariants below hold after every call:
 * <ul>
 *   <li>sum of all ledger balances = 0 (no points created or destroyed);</li>
 *   <li>campaign budget = available + reserved + awarded + refunded;</li>
 *   <li>customer balance = sum of that customer's unexpired lot remainders.</li>
 * </ul>
 */
public final class PointsEngine {

    private final Ledger ledger;
    private final LongSupplier clock;
    private final long maxRewardBp;
    private final Duration validity;

    private final Map<String, CampaignState> campaigns = new LinkedHashMap<>();
    private final Map<String, List<CampaignState>> campaignsBySeller = new HashMap<>();
    private final Map<String, OrderState> orders = new HashMap<>();
    private final Map<String, TreeSet<Lot>> lots = new HashMap<>();
    private final Map<String, Long> redemptions = new HashMap<>();
    private long lotSeq = 0;

    public PointsEngine(Ledger ledger, LongSupplier clock, long maxRewardBp, Duration validity) {
        this.ledger = ledger;
        this.clock = clock;
        this.maxRewardBp = maxRewardBp;
        this.validity = validity;
    }

    // ------------------------------------------------------------------ campaigns

    public CampaignView createCampaign(Campaign c) {
        Ledger.checkName(c.id());
        Ledger.checkName(c.sellerId());
        return ledger.atomically(() -> {
            CampaignState existing = campaigns.get(c.id());
            if (existing != null) {
                if (existing.campaign.equals(c)) return view(existing); // idempotent retry
                throw new ConflictException("campaign " + c.id() + " already exists with different terms");
            }
            ledger.transfer("seller:" + c.sellerId(), acct(c), c.budget(), "FUND", c.id(), now());
            CampaignState s = new CampaignState(c);
            campaigns.put(c.id(), s);
            campaignsBySeller.computeIfAbsent(c.sellerId(), k -> new ArrayList<>()).add(s);
            return view(s);
        });
    }

    public CampaignView closeCampaign(String id) {
        return ledger.atomically(() -> {
            CampaignState s = campaign(id);
            if (!s.closed) {
                long left = ledger.balance(acct(s.campaign));
                if (left > 0) ledger.transfer(acct(s.campaign), "seller:" + s.campaign.sellerId(), left, "REFUND", id, now());
                s.refunded += left;
                s.closed = true;
            }
            return view(s);
        });
    }

    public List<CampaignView> campaigns() {
        return ledger.atomically(() -> campaigns.values().stream().map(this::view).toList());
    }

    // ------------------------------------------------------------------ orders

    /** Places an order and reserves its points. Re-sending the same order is safe (idempotent). */
    public OrderView placeOrder(OrderRequest req) {
        Ledger.checkName(req.orderId());
        Ledger.checkName(req.customerId());
        req.lines().forEach(l -> Ledger.checkName(l.sellerId()));
        return ledger.atomically(() -> {
            OrderState existing = orders.get(req.orderId());
            if (existing != null) {
                if (existing.request.equals(req)) return view(existing);
                throw new ConflictException("order " + req.orderId() + " already exists with a different body");
            }
            long t = now();
            Map<String, Long> bySeller = new HashMap<>();
            long total = 0;
            for (Line l : req.lines()) {
                long v = l.taxExclusive();
                bySeller.merge(l.sellerId(), v, Long::sum);
                total += v;
            }
            long orderCap = total * maxRewardBp / 10_000;

            List<Candidate> candidates = new ArrayList<>();
            for (CampaignState s : eligible(bySeller.keySet())) {
                Campaign c = s.campaign;
                long base = bySeller.get(c.sellerId());
                if (s.closed || base < c.minSubtotal() || !c.activeAt(t)) continue;
                long pts = Math.min(c.perOrderCap(), base * c.rateBp() / 10_000);
                if (pts > 0 && pts <= ledger.balance(acct(c))) candidates.add(new Candidate(c.id(), c.group(), pts));
            }
            StackingOptimizer.Selection sel = StackingOptimizer.best(candidates, orderCap);
            long greedy = StackingOptimizer.greedy(candidates, orderCap).totalPoints();

            List<Award> awards = new ArrayList<>();
            for (Candidate c : sel.chosen()) {
                CampaignState s = campaigns.get(c.campaignId());
                ledger.transfer(acct(s.campaign), hold(req.orderId()), c.points(), "RESERVE", c.campaignId(), t);
                s.reserved += c.points();
                awards.add(new Award(c.campaignId(), c.points()));
            }
            OrderState o = new OrderState(req, total, orderCap, awards, greedy);
            orders.put(req.orderId(), o);
            return view(o);
        });
    }

    /** Shipment makes the reserved points spendable by the customer, as a lot with an expiry date. */
    public OrderView ship(String orderId) {
        return ledger.atomically(() -> {
            OrderState o = order(orderId);
            if (o.status == OrderStatus.SHIPPED) return view(o);
            if (o.status == OrderStatus.CANCELLED) throw new ConflictException("order " + orderId + " was cancelled");
            long t = now();
            long expiresAt = t + validity.toMillis();
            for (Award a : o.awards) {
                ledger.transfer(hold(orderId), customer(o.request.customerId()), a.points(), "AWARD", a.campaignId(), t);
                CampaignState s = campaigns.get(a.campaignId());
                s.reserved -= a.points();
                s.awarded += a.points();
            }
            long sum = o.totalPoints();
            if (sum > 0) lots.computeIfAbsent(o.request.customerId(), k -> new TreeSet<>(Lot.ORDER))
                    .add(new Lot(++lotSeq, orderId, expiresAt, sum));
            o.status = OrderStatus.SHIPPED;
            return view(o);
        });
    }

    /** Cancelling before shipment returns the reserved points to each campaign's budget. */
    public OrderView cancel(String orderId) {
        return ledger.atomically(() -> {
            OrderState o = order(orderId);
            if (o.status == OrderStatus.CANCELLED) return view(o);
            if (o.status == OrderStatus.SHIPPED) throw new ConflictException("order " + orderId + " already shipped");
            for (Award a : o.awards) {
                CampaignState s = campaigns.get(a.campaignId());
                ledger.transfer(hold(orderId), acct(s.campaign), a.points(), "RELEASE", a.campaignId(), now());
                s.reserved -= a.points();
            }
            o.status = OrderStatus.CANCELLED;
            return view(o);
        });
    }

    // ------------------------------------------------------------------ customers

    /** Spends points, oldest-expiring first. {@code redemptionId} makes retries safe. */
    public CustomerView redeem(String customerId, long points, String redemptionId) {
        Ledger.checkName(customerId);
        Ledger.checkName(redemptionId);
        if (points <= 0) throw new IllegalArgumentException("points must be > 0");
        return ledger.atomically(() -> {
            Long prev = redemptions.get(redemptionId);
            if (prev != null) {
                if (prev != points) throw new ConflictException("redemption " + redemptionId + " reused with a different amount");
                return customerView(customerId);
            }
            expireDue(customerId, now());
            long bal = ledger.balance(customer(customerId));
            if (bal < points) throw new InsufficientFundsException(customer(customerId), bal, points);
            ledger.transfer(customer(customerId), "system:redeemed", points, "REDEEM", redemptionId, now());
            long left = points;
            Iterator<Lot> it = lots.getOrDefault(customerId, new TreeSet<>(Lot.ORDER)).iterator();
            while (left > 0 && it.hasNext()) {
                Lot lot = it.next();
                long take = Math.min(left, lot.remaining);
                lot.remaining -= take;
                left -= take;
                if (lot.remaining == 0) it.remove();
            }
            redemptions.put(redemptionId, points);
            return customerView(customerId);
        });
    }

    /** Moves every lot past its expiry to the breakage account. Returns points expired. */
    public long expireAll() {
        return ledger.atomically(() -> {
            long t = now(), total = 0;
            for (String c : new ArrayList<>(lots.keySet())) total += expireDue(c, t);
            return total;
        });
    }

    public CustomerView customerView(String customerId) {
        return ledger.atomically(() -> {
            List<LotView> lv = lots.getOrDefault(customerId, new TreeSet<>(Lot.ORDER)).stream()
                    .map(l -> new LotView(l.orderId, l.remaining, l.expiresAt)).toList();
            return new CustomerView(customerId, ledger.balance(customer(customerId)), lv);
        });
    }

    public OrderView orderView(String orderId) {
        return ledger.atomically(() -> view(order(orderId)));
    }

    // ------------------------------------------------------------------ audit

    public record Audit(Ledger.VerifyReport ledger, boolean campaignsBalanced, boolean lotsMatchBalances,
                        long overspentCampaigns, boolean ok) {}

    /** Re-checks every invariant from scratch. Used by tests, benchmarks and GET /audit. */
    public Audit audit() {
        return ledger.atomically(() -> {
            Ledger.VerifyReport r = ledger.verify();
            boolean campaignsOk = true;
            long overspent = 0;
            for (CampaignState s : campaigns.values()) {
                long available = ledger.balance(acct(s.campaign));
                if (available < 0) overspent++;
                if (s.campaign.budget() != available + s.reserved + s.awarded + s.refunded) campaignsOk = false;
            }
            boolean lotsOk = true;
            for (var e : lots.entrySet()) {
                long sum = e.getValue().stream().mapToLong(l -> l.remaining).sum();
                if (sum != ledger.balance(customer(e.getKey()))) lotsOk = false;
            }
            return new Audit(r, campaignsOk, lotsOk, overspent, r.ok() && campaignsOk && lotsOk && overspent == 0);
        });
    }

    public Ledger ledger() { return ledger; }

    // ------------------------------------------------------------------ internals

    private long expireDue(String customerId, long t) {
        TreeSet<Lot> set = lots.get(customerId);
        if (set == null) return 0;
        long total = 0;
        Iterator<Lot> it = set.iterator();
        while (it.hasNext()) {
            Lot lot = it.next();
            if (lot.expiresAt > t) break; // sorted by expiry: nothing later is due
            ledger.transfer(customer(customerId), "system:breakage", lot.remaining, "EXPIRE", lot.orderId, t);
            total += lot.remaining;
            it.remove();
        }
        return total;
    }

    /** Only the campaigns of sellers in this order, via an index (not a scan of every campaign). */
    private List<CampaignState> eligible(Set<String> sellers) {
        List<CampaignState> out = new ArrayList<>();
        for (String seller : sellers) out.addAll(campaignsBySeller.getOrDefault(seller, List.of()));
        return out;
    }

    private long now() { return clock.getAsLong(); }

    private static String acct(Campaign c) { return "campaign:" + c.id(); }
    private static String hold(String orderId) { return "hold:" + orderId; }
    private static String customer(String id) { return "customer:" + id; }

    private CampaignState campaign(String id) {
        CampaignState s = campaigns.get(id);
        if (s == null) throw new NotFoundException("no campaign " + id);
        return s;
    }

    private OrderState order(String id) {
        OrderState o = orders.get(id);
        if (o == null) throw new NotFoundException("no order " + id);
        return o;
    }

    private CampaignView view(CampaignState s) {
        return new CampaignView(s.campaign, ledger.balance(acct(s.campaign)), s.reserved, s.awarded, s.refunded, s.closed);
    }

    private OrderView view(OrderState o) {
        return new OrderView(o.request.orderId(), o.request.customerId(), o.status, o.taxExclusiveTotal, o.orderCap,
                List.copyOf(o.awards), o.totalPoints(), o.greedyPoints);
    }

    private static final class CampaignState {
        final Campaign campaign;
        long reserved, awarded, refunded;
        boolean closed;
        CampaignState(Campaign c) { this.campaign = c; }
    }

    private static final class OrderState {
        final OrderRequest request;
        final long taxExclusiveTotal, orderCap, greedyPoints;
        final List<Award> awards;
        OrderStatus status = OrderStatus.PLACED;

        OrderState(OrderRequest r, long total, long cap, List<Award> awards, long greedy) {
            this.request = r;
            this.taxExclusiveTotal = total;
            this.orderCap = cap;
            this.awards = awards;
            this.greedyPoints = greedy;
        }

        long totalPoints() { return awards.stream().mapToLong(Award::points).sum(); }
    }

    private static final class Lot {
        static final Comparator<Lot> ORDER = Comparator.<Lot>comparingLong(l -> l.expiresAt).thenComparingLong(l -> l.id);
        final long id, expiresAt;
        final String orderId;
        long remaining;

        Lot(long id, String orderId, long expiresAt, long remaining) {
            this.id = id;
            this.orderId = orderId;
            this.expiresAt = expiresAt;
            this.remaining = remaining;
        }
    }
}
