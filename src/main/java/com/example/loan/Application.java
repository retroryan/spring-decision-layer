package com.example.loan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application, and nothing else. What used to live here, a {@code CommandLineRunner} that
 * decided one loan on startup, is now the {@code decide} command in {@link DecisionCommands}: the
 * decision runs when it is asked for by name, not the moment the context comes up. Seed loading is
 * in {@link SeedConfig}. This class's package is what sets the component-scan root, which is the
 * one thing about it worth being careful with.
 */
@SpringBootApplication
public class Application {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

}
