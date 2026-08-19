package com.example.loan;

/**
 * What one run produces: the decision the policy engine computed, and the sentence the model
 * wrote about it. Both are printed, so a reader can check the prose against the arithmetic.
 */
record LoanAnswer(LoanDecision decision, String explanation) {
}
