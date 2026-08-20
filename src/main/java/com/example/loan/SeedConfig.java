package com.example.loan;

import java.io.IOException;
import java.io.InputStream;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * Loads {@code classpath:/seed.json} into a {@link Seed} bean, once, using Spring's own
 * collaborators: the {@link JsonMapper} Boot autoconfigures (so nothing here builds a mapper) and
 * the {@code @Value("classpath:/seed.json")} {@link Resource} the framework resolves off the
 * classpath. {@link GraphSeeder} takes the {@link Seed} by injection.
 *
 * <p>It is its own {@code @Configuration} rather than a method on {@link Application} so a test can
 * {@code @Import} it (with {@code JacksonAutoConfiguration}) to get just the seed bean, without
 * booting the whole application. In the app itself it is picked up by component scan like any other
 * bean in this package.
 */
@Configuration(proxyBeanMethods = false)
class SeedConfig {

	@Bean
	Seed seed(JsonMapper jsonMapper, @Value("classpath:/seed.json") Resource seedJson) {
		try (InputStream stream = seedJson.getInputStream()) {
			return jsonMapper.readValue(stream, Seed.class);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not read /seed.json. Restore it with: "
					+ "git checkout src/main/resources/seed.json", ex);
		}
	}

}
