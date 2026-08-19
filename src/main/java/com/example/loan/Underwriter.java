package com.example.loan;

/**
 * One of the people on the roster, read back out of the graph rather than out of a file, because
 * the draw and the fourth node of the context graph are the same object.
 *
 * A person and not a mood: the label is the short form the console prints, and the disposition is
 * the line that goes in the prompt, which is where the variation between two runs of identical
 * arguments comes from now that there is no temperature to turn.
 */
record Underwriter(String underwriterId, String name, String title, long yearsOnTheJob,
		String label, String disposition) {
}
