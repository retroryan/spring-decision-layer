package com.example.loan;

/**
 * One row of the read back: a person who granted an exception, the person whose denial it set
 * aside, which decision, and why.
 *
 * It is the reason {@code GRANTED_BY} is worth adding beside {@code EXCEPTION_TO}: both ends are
 * nodes, so the sentence a granted exception makes is a traversal across {@code Underwriter},
 * {@code Exception}, and {@code Decision} rather than a string property read twice.
 */
record ExceptionGrant(String grantedBy, String decidedBy, String decisionId, String justification) {
}
