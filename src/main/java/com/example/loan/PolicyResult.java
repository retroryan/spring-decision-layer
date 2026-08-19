package com.example.loan;

/**
 * The outcome of checking one policy against one application. The observed value and the
 * threshold stay numbers because they are stored on the APPLIED_POLICY edge; the detail string
 * is what the console prints, so the graph and the console cannot drift apart.
 */
record PolicyResult(String key, String name, boolean passed, double observed, double threshold,
		String detail) {
}
