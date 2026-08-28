package com.example.loan;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.EnabledIf;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A live check that the configured model actually honours native structured output, which is the
 * one thing the rest of the suite cannot cover because it mocks the model away. The demo relies on
 * the endpoint enforcing the schema at the API level so a {@link LoanVerdict} comes back valid by
 * construction rather than scraped out of prose. When it does not (an earlier model returned a
 * reasoning preamble and the parser threw on the first token), this is where it shows up.
 *
 * <p>{@code useProviderStructuredOutput()} is Spring AI's switch for exactly that: it sends the
 * generated schema to the provider as a request-level constraint rather than as prompt text, which
 * is the native path. {@code .entity(...)} then deserializes the reply into the record. Using
 * {@link ChatClient#create(ChatModel)} means the call carries the model the bean was configured
 * with ({@code xai.grok-4.6}) rather than the SDK's default.
 *
 * <p>It calls the real model, so it costs a request and only runs when a key is configured. The
 * gate is on the {@code spring.ai.openai.api-key} property (which reads
 * {@code ${AWS_BEARER_TOKEN_BEDROCK}}) rather than on the raw environment variable, so it tracks
 * the same setting the model beans consume. {@code loadContext = true} loads this scoped context
 * to read that property; the test property carries an empty default so an absent key resolves to
 * a blank string here (and to no-auth in the client) rather than failing context startup.
 *
 * <p>The context is scoped to the model beans alone. It does not boot {@link Application}, so there
 * is no graph, no Neo4j, and no shell; the base URL, key and model all come from
 * {@code src/test/resources/application.properties}, the same ones a real run uses.
 */
@SpringBootTest(classes = NativeStructuredOutputTests.ModelOnly.class)
@EnabledIf(expression = "#{!'${spring.ai.openai.api-key:}'.isBlank()}", loadContext = true,
		reason = "Requires spring.ai.openai.api-key (AWS_BEARER_TOKEN_BEDROCK) to be set")
class NativeStructuredOutputTests {

	@Autowired
	private ChatModel chatModel;

	@Test
	void theModelReturnsAVerdictThatFillsTheSchema() {
		// The assertion that matters is that .entity(...) does not throw: with the schema enforced
		// at the provider, the reply is JSON matching LoanVerdict and deserializes; prose or a
		// wrapped object would not.
		LoanVerdict verdict = ChatClient.create(this.chatModel)
			.prompt()
			.system("You underwrite construction loans at a bank. Decide the application and fill "
					+ "in the verdict.")
			.user("Ridgeline Builders (C-1042) asks for a $250,000 construction loan. Credit score "
					+ "72. With this loan its debt-to-income sits at 48%, against a 40% limit. "
					+ "Nothing else is on file. Decide it.")
			.call()
			.entity(LoanVerdict.class, ChatClient.EntityParamSpec::useProviderStructuredOutput);

		assertThat(verdict).isNotNull();
		assertThat(verdict.outcome()).isNotNull();
		assertThat(verdict.confidence()).isNotNull();
		assertThat(verdict.reason()).isNotBlank();
		assertThat(verdict.explanation()).isNotBlank();
		// Required by the schema even when history did not move the call, so it is present and
		// possibly empty rather than null.
		assertThat(verdict.citedDecisionIds()).isNotNull();
	}

	/**
	 * Just the OpenAI chat model and the tool-calling manager it depends on. Nothing scans
	 * {@code com.example.loan}, so none of the app's own beans, and none of Neo4j, load.
	 */
	@Configuration(proxyBeanMethods = false)
	@ImportAutoConfiguration({ ToolCallingAutoConfiguration.class, OpenAiChatAutoConfiguration.class })
	static class ModelOnly {

	}

}
