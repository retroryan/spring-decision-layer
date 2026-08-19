package com.example.loan;

/**
 * One bank rule. The threshold is a property on a Policy node rather than a constant in code, so
 * the numbers a decision was checked against are queryable next to the decisions that were
 * checked against them.
 *
 * windowMonths is how far back the rule looks, and only Repeat Denial Escalation sets it: it is
 * the one rule that reads precedent, so it is the one rule that needs a horizon. The others
 * leave it 0 and never ask.
 */
record Policy(String key, String name, double threshold, long windowMonths, String description) {
}
