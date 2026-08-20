package com.example.loan;

import org.testcontainers.neo4j.Neo4jContainer;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one bean {@link TestApplication} adds on top of the real application: a Neo4j started in a
 * container and handed to the app as its database.
 *
 * <p>{@code @ServiceConnection} is the whole point. Spring Boot's
 * {@code Neo4jContainerConnectionDetailsFactory} turns this container into a
 * {@code Neo4jConnectionDetails} bean, which takes precedence over the {@code spring.neo4j.*}
 * properties in {@code application.properties}. So the driver connects to this container's bolt URL and
 * admin password rather than to the {@code NEO4J_URI} an {@code .env} would supply, and
 * {@code ./mvnw spring-boot:test-run} needs no Neo4j of its own.
 *
 * <p>It is a plain {@code @Configuration} in the {@code com.example.loan} package, so
 * {@link Application}'s component scan finds it when {@code spring-boot:test-run} puts the test
 * classes on the classpath. That is why {@link TestApplication} does not have to name it: running
 * {@code SpringApplication.from(Application::main)} is enough, and this container joins the context
 * on its own.
 *
 * <p>The tests do not rely on that scan. {@link LoanGraphTests} pulls this in through an explicit
 * {@code @Import} on a context scoped to Neo4j alone, so it gets the container without booting the
 * model layer. No test boots the full {@link Application} context, which is the only thing that
 * would pick this up by scanning; if one ever does, it will get this container too, which is the
 * intent for a test that wants the whole application.
 *
 * <p>The image matches the one the README and {@link LoanGraphTests} use, so the demo runs against
 * the same Neo4j version whether the database is a container or the reader's own instance.
 */
@Configuration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	Neo4jContainer neo4jContainer() {
		return new Neo4jContainer("neo4j:5.26");
	}

}
