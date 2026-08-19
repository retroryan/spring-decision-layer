///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.zeroturnaround:zt-exec:1.12
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.1
//JAVA 17
//FILES ExampleInfo.json
//SOURCES ../../../integration-testing/jbang-lib/IntegrationTestUtils.java

/*
 * Integration test launcher for context-graph
 * Tests a CallAdvisor that reads precedent from Neo4j, decides a loan by policy code,
 * writes the decision back to the graph, then has the model explain that verdict
 */

public class RunContextGraph {

    public static void main(String... args) throws Exception {
        IntegrationTestUtils.runIntegrationTest("context-graph");
    }
}
