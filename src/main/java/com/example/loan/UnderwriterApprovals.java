package com.example.loan;

/**
 * One row of the read back: a person, a line they have approved a loan past, and how many times.
 *
 * It is the reason Underwriter is not a node this demo only ever writes to. Both ends are nodes
 * and both hops are relationships, so the claim the demo makes out loud is a traversal rather
 * than a sentence on a slide.
 */
record UnderwriterApprovals(String name, String policyName, long approvals) {
}
