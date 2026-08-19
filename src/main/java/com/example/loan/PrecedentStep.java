package com.example.loan;

/**
 * One later decision that a denial has since driven, with how many ESCALATED_FROM hops away it
 * is. Depth 1 cited the denial directly; depth 2 cited a decision that cited it.
 *
 * The depth is what makes the trail a chain rather than a parent link, and it comes from
 * length(path) on a variable-length traversal, which is the read a relational schema has to
 * write a recursive CTE for.
 */
record PrecedentStep(long depth, String decisionId) {
}
