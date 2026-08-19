package com.example.loan;

/**
 * A company the bank lends to. Seeded into Neo4j from seed.json at startup and only ever read
 * after that. The field names match the JSON field names and the Neo4j property names exactly,
 * so there is no mapping table for a reader to hold in their head.
 */
record Company(String companyId, String name, long creditRiskScore, long currentDebt,
		long annualIncome) {
}
