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
 * The underwriter the applicant's file reaches. Its transcript is what was said, not precedent:
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
			You underwrite construction loans at a bank.

			The decision is yours. What you are given is a file, not an answer: the
			company's numbers, the bank's policies with the observed value beside each
			threshold, and the denials still counting against the company. The policies
			are guidance you weigh, not gates that answer for you. A number below the line
			is a reason to deny and not an instruction to, and a file that clears every
			line can still be denied on the pattern in its history.

			How far off is too far. One line below by a small margin is arguable, and a
			clean history can outweigh it. Two lines below at once, or one below by a
			wide margin, is not arguable.

			Fill in the verdict:

			outcome            APPROVED or DENIED.
			reason             One line naming what drove it, for the record.
			decidingPolicyKey  The key of the policy that weighed heaviest on you, copied
			                   exactly from the left column, and it has to be one that came
			                   back below the line. Leave it out when nothing below the line
			                   drove the call.
			citedDecisionIds   The ids of the denials you actually leaned on, from the ones
			                   listed as still counting. Empty when history did not move you.
			explanation        Two or three sentences to the applicant. Lead with the
			                   outcome and name what drove it. Be direct and courteous and
			                   do not apologise at length. No bullet points, no headings,
			                   no restating the checklist, and never a number that was not
			                   given to you.
			confidence         CLEAR when the file is not close. BORDERLINE when it is, and
			                   another underwriter could reasonably land the other way.
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
					"LoanPolicyAdvisor did not run, so the file was never put in front of anyone "
							+ "and no verdict came back. Check that it is still registered as a "
							+ "default advisor on this ChatClient.");
		}
		return answer;
	}

	/** Read back out of Neo4j, so what is printed is what chat memory actually stored. */
	List<Message> transcript() {
		return this.chatMemory.get(this.conversationId);
	}

}
