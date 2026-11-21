# ADR-0002: Exact branch-and-bound for campaign stacking

* Status: Accepted
* Context: [design doc §3.4](../design/0001-pointledger-design.md)

## Context

An order may qualify for many seller campaigns. At most one campaign per exclusive group may
apply, each has its own per-order cap, and the platform caps total points per order. "Take the
largest offer first" ignores how the cap binds and leaves points on the table.

## Decision

Model the choice as a multiple-choice knapsack and solve it exactly with branch-and-bound
(`StackingOptimizer`), keeping the greedy result alongside for comparison in the API response.

## Consequences

* Matched brute force on 20,000 random orders; greedy was suboptimal on 36.1% of them,
  ≈33 points short when it was.
* Worst-case time is exponential in the number of eligible campaigns; in practice orders have
  few, and the bound prunes quickly. If that changes, add a node limit with a greedy fallback.

## Alternatives

Greedy (rejected above); an external MIP solver (too heavy for the problem size).
