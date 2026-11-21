package dev.pointledger.promo;

import java.util.*;

/**
 * Chooses which seller-funded point campaigns apply to one order.
 *
 * <p>Rules: a campaign applies whole or not at all (its advertised bonus is all-or-nothing);
 * campaigns in the same exclusivity group cannot stack; and the platform caps total points per
 * order. Maximising points under these rules is a multiple-choice knapsack problem (NP-hard in
 * general), so a greedy "take the biggest first" pass can leave points on the table when the cap
 * binds. {@link #best} solves it exactly with depth-first branch-and-bound: groups are explored
 * in order of their best option, and a branch is cut as soon as its optimistic bound
 * (current sum + best option of every remaining group, clipped to the cap) cannot beat the best
 * solution found so far.
 */
public final class StackingOptimizer {

    /** One eligible campaign for this order and the points it would award. */
    public record Candidate(String campaignId, String group, long points) {}

    public record Selection(List<Candidate> chosen, long totalPoints, long nodesVisited) {}

    private StackingOptimizer() {}

    public static Selection best(List<Candidate> candidates, long orderCap) {
        List<List<Candidate>> groups = groupsSorted(candidates, orderCap);
        int g = groups.size();
        long[] suffixMax = new long[g + 1];
        for (int i = g - 1; i >= 0; i--) suffixMax[i] = suffixMax[i + 1] + groups.get(i).get(0).points();

        Search s = new Search(groups, suffixMax, orderCap);
        s.dfs(0, 0, new ArrayDeque<>());
        List<Candidate> chosen = new ArrayList<>(s.bestPick);
        chosen.sort(Comparator.comparing(Candidate::campaignId));
        return new Selection(chosen, s.bestSum, s.nodes);
    }

    /** Baseline: biggest award first, skip if its group is used or it would break the cap. */
    public static Selection greedy(List<Candidate> candidates, long orderCap) {
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(ORDER);
        Set<String> used = new HashSet<>();
        List<Candidate> chosen = new ArrayList<>();
        long sum = 0;
        for (Candidate c : sorted) {
            if (c.points() <= 0 || sum + c.points() > orderCap || used.contains(groupOf(c))) continue;
            used.add(groupOf(c));
            chosen.add(c);
            sum += c.points();
        }
        chosen.sort(Comparator.comparing(Candidate::campaignId));
        return new Selection(chosen, sum, chosen.size());
    }

    /** Reference answer by full enumeration (tests only; exponential). */
    public static long bruteForce(List<Candidate> candidates, long orderCap) {
        int n = candidates.size();
        long best = 0;
        for (int mask = 0; mask < (1 << n); mask++) {
            long sum = 0;
            Set<String> used = new HashSet<>();
            boolean ok = true;
            for (int i = 0; i < n && ok; i++) {
                if ((mask & (1 << i)) == 0) continue;
                Candidate c = candidates.get(i);
                ok = used.add(groupOf(c));
                sum += c.points();
            }
            if (ok && sum <= orderCap) best = Math.max(best, sum);
        }
        return best;
    }

    // ---------------------------------------------------------------------------------------

    private static final Comparator<Candidate> ORDER =
            Comparator.comparingLong(Candidate::points).reversed().thenComparing(Candidate::campaignId);

    private static String groupOf(Candidate c) {
        return c.group() == null || c.group().isEmpty() ? "solo:" + c.campaignId() : c.group();
    }

    private static List<List<Candidate>> groupsSorted(List<Candidate> candidates, long cap) {
        Map<String, List<Candidate>> byGroup = new HashMap<>();
        for (Candidate c : candidates) {
            if (c.points() > 0 && c.points() <= cap) byGroup.computeIfAbsent(groupOf(c), k -> new ArrayList<>()).add(c);
        }
        List<List<Candidate>> groups = new ArrayList<>(byGroup.values());
        for (List<Candidate> l : groups) l.sort(ORDER);
        groups.sort(Comparator.<List<Candidate>>comparingLong(l -> l.get(0).points()).reversed()
                .thenComparing(l -> l.get(0).campaignId()));
        return groups;
    }

    private static final class Search {
        final List<List<Candidate>> groups;
        final long[] suffixMax;
        final long cap;
        long bestSum = 0;
        List<Candidate> bestPick = List.of();
        long nodes = 0;

        Search(List<List<Candidate>> groups, long[] suffixMax, long cap) {
            this.groups = groups;
            this.suffixMax = suffixMax;
            this.cap = cap;
        }

        void dfs(int i, long sum, Deque<Candidate> pick) {
            nodes++;
            if (sum > bestSum) {
                bestSum = sum;
                bestPick = new ArrayList<>(pick);
                if (bestSum == cap) return; // cannot do better than the cap
            }
            if (i == groups.size()) return;
            if (Math.min(cap, sum + suffixMax[i]) <= bestSum) return; // bound: prune
            for (Candidate c : groups.get(i)) {
                if (sum + c.points() > cap) continue;
                pick.addLast(c);
                dfs(i + 1, sum + c.points(), pick);
                pick.removeLast();
                if (bestSum == cap) return;
            }
            dfs(i + 1, sum, pick); // skip this group
        }
    }
}
