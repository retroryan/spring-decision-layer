package com.example.loan;

import org.springframework.boot.SpringApplication;

/**
 * The real {@link Application}, launched against a Neo4j that lives only as long as the run does.
 * Start it with:
 *
 * <pre>{@code
 * ./mvnw spring-boot:test-run
 * }</pre>
 *
 * <p>The {@code test-run} goal resolves its main class from the test classes, so this is the class
 * it starts. {@link SpringApplication#from} runs {@code Application.main} unchanged. The Neo4j
 * container comes from {@link TestcontainersConfiguration}, a {@code @Configuration} in this
 * package that {@code Application}'s component scan picks up off the test classpath, so its
 * {@code @ServiceConnection} container becomes the database the app reads and writes. Nothing in
 * {@code src/main} knows the difference.
 *
 * <p>The database needs no environment variable. {@code src/test/resources/application.properties}
 * shadows the real config on the test classpath and omits the {@code ${NEO4J_URI}} /
 * {@code ${NEO4J_PASSWORD}} placeholders, and the {@code @ServiceConnection} container supplies the
 * bolt URL and admin password the driver actually connects with. {@code AWS_BEARER_TOKEN_BEDROCK}
 * is the one thing still read from the environment, since the app calls the model to decide the
 * loan.
 */
public final class TestApplication {

	private TestApplication() {
	}

	public static void main(String[] args) {
		SpringApplication.from(Application::main).run(args);
	}

}
