package com.example.loan;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Which generation the verdict is read out of, which nothing here could catch before, because
 * nothing here calls the model. Every live run failed on it: claude-sonnet-5 thinks adaptively,
 * AnthropicChatModel gives each thinking block a Generation of its own and appends the text one
 * last, and ChatResponse.getResult() returns the first. The verdict was in the response the whole
 * time, sitting behind an empty thinking block.
 *
 * The shapes below are the ones the API actually sent, taken off the wire: a thinking block whose
 * text is empty and whose signature is in the properties, then the JSON.
 */
class LoanPolicyAdvisorTests {

	private static final String VERDICT = "{\"outcome\":\"DENIED\",\"reason\":\"Too far off.\"}";

	@Test
	void theVerdictIsReadFromTheGenerationBehindTheThinkingBlock() {
		List<Generation> generations = List.of(thinking(""), answer());

		assertThat(LoanPolicyAdvisor.verdictGeneration(generations).getOutput().getText())
			.isEqualTo(VERDICT);
	}

	/**
	 * Thinking that is not empty is the case a first-generation read would have appeared to
	 * survive, by handing the converter prose that parses as nothing.
	 */
	@Test
	void thinkingWithSomethingInItStillDoesNotWin() {
		List<Generation> generations = List.of(thinking("Let me weigh the debt ratio here."),
				answer());

		assertThat(LoanPolicyAdvisor.verdictGeneration(generations).getOutput().getText())
			.isEqualTo(VERDICT);
	}

	/** A redacted thinking block carries a marker and no text at all, so there is nothing to read. */
	@Test
	void aRedactedThinkingBlockIsSkippedTheSameWay() {
		Generation redacted = new Generation(
				AssistantMessage.builder().properties(Map.of("data", "EroBCk")).build());

		List<Generation> generations = List.of(redacted, answer());

		assertThat(LoanPolicyAdvisor.verdictGeneration(generations).getOutput().getText())
			.isEqualTo(VERDICT);
	}

	/** One generation and no thinking at all is the ordinary case, and it still works. */
	@Test
	void aResponseThatDidNotThinkIsReadTheSameWay() {
		assertThat(LoanPolicyAdvisor.verdictGeneration(List.of(answer())).getOutput().getText())
			.isEqualTo(VERDICT);
	}

	/**
	 * Nothing to fall back on, because the verdict is the model's now. The run fails and the
	 * operator runs it again, which is the honest answer when nobody decided anything.
	 */
	@Test
	void aResponseThatIsAllThinkingAndNoAnswerFailsTheRun() {
		assertThatIllegalStateException()
			.isThrownBy(() -> LoanPolicyAdvisor.verdictGeneration(List.of(thinking(""))))
			.withMessageContaining("empty answer");
	}

	@Test
	void aResponseWithNoGenerationsAtAllFailsTheRun() {
		assertThatIllegalStateException()
			.isThrownBy(() -> LoanPolicyAdvisor.verdictGeneration(List.of()))
			.withMessageContaining("empty answer");
	}

	private static Generation thinking(String thought) {
		return new Generation(AssistantMessage.builder()
			.content(thought)
			.properties(Map.of("signature", "ErMLCpAB"))
			.build());
	}

	private static Generation answer() {
		return new Generation(new AssistantMessage(VERDICT));
	}

}
