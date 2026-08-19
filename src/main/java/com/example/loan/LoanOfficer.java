package com.example.loan;

import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.neo4j.Neo4jChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

/**
 * The chat client the applicant talks to. Its transcript is what was said, not precedent:
 * Spring AI's own chat memory, stored by {@link Neo4jChatMemoryRepository} in the same database
 * as the context graph, on the library's own {@code (:Session)-[:HAS_MESSAGE]->(:Message)}
 * schema.
 *
 * The conversation id is a fresh UUID per run, deliberately. Keying it to the company would
 * replay the previous run's prose into this run's prompt; what survives across runs is the
 * context graph, not generated English.
 */
@Component
class LoanOfficer {

	private static final String SYSTEM = """
			You work at a bank, explaining construction loan decisions to the companies
			that applied for them.

			The decision is not yours to make. Every request you receive already carries
			the outcome, the reason, and the policy checks behind it, decided by the bank's
			policy engine before you saw it. Your only job is to say it back to the
			applicant in plain English.

			Write two or three sentences. Lead with the outcome. Name the one policy that
			decided it and what about their numbers triggered it. Be direct and courteous,
			and do not apologise at length. No bullet points, no headings, no restating the
			whole checklist, and never a number that was not given to you.
			""";

	private final ChatClient chatClient;

	private final ChatMemory chatMemory;

	private final String conversationId = UUID.randomUUID().toString();

	LoanOfficer(ChatClient.Builder builder, LoanPolicyAdvisor loanPolicyAdvisor,
			Neo4jChatMemoryRepository chatMemoryRepository) {

		this.chatMemory = MessageWindowChatMemory.builder()
			.chatMemoryRepository(chatMemoryRepository)
			.build();

		this.chatClient = builder.defaultSystem(SYSTEM)
			.defaultAdvisors(MessageChatMemoryAdvisor.builder(this.chatMemory).build(),
					loanPolicyAdvisor)
			.defaultAdvisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID,
					this.conversationId))
			.build();
	}

	LoanAnswer answer(String companyId, long requestedAmount) {
		String question = "Can %s get a construction loan of $%,d?".formatted(companyId,
				requestedAmount);

		ChatClientResponse response = this.chatClient.prompt()
			.user(question)
			.advisors(advisor -> advisor.param(LoanPolicyAdvisor.COMPANY_ID, companyId)
				.param(LoanPolicyAdvisor.REQUESTED_AMOUNT, requestedAmount))
			.call()
			.chatClientResponse();

		LoanAnswer answer = (LoanAnswer) response.context().get(LoanPolicyAdvisor.ANSWER);
		if (answer == null) {
			throw new IllegalStateException(
					"LoanPolicyAdvisor did not run, so nothing computed a verdict. Check that it "
							+ "is still registered as a default advisor on this ChatClient.");
		}
		return answer;
	}

	/** Read back out of Neo4j, so what is printed is what chat memory actually stored. */
	List<Message> transcript() {
		return this.chatMemory.get(this.conversationId);
	}

}
