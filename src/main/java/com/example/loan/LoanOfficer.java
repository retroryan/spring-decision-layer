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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * The underwriter the applicant's file reaches. Its transcript is what was said, not precedent:
 * Spring AI's own chat memory, stored by {@link Neo4jChatMemoryRepository} in the same database
 * as the context graph, on the library's own {@code (:Session)-[:HAS_MESSAGE]->(:Message)}
 * schema.
 *
 * The conversation id is a fresh UUID per call, deliberately. Keying it to the company would
 * replay the previous run's prose into this run's prompt; what survives across runs is the
 * context graph, not generated English.
 */
@Component
class LoanOfficer {

	/**
	 * The role, which is the same whoever is on duty. Who is on duty is not here: it changes on
	 * every run, and a system prompt that changes on every run is a prompt cache that misses on
	 * every run. {@link PrecedentAdvisor} appends that to the user message beside the facts.
	 */
	private static final String SYSTEM = """
			You underwrite construction loans at a bank.

			The decision is yours. What you are given is a file, not an answer: the
			company's numbers, the bank's policies with the observed value beside each
			threshold, and the denials still counting against the company. The policies
			are guidance you weigh, not gates that answer for you. A number below the line
			is a reason to deny and not an instruction to, and a file that clears every
			line can still be denied on the pattern in its history.

			Who you are is at the end of the file below, with how you have come to read
			one. Read the file as that person and decide as they would.

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
			                   outcome and name what drove it, and sign it with your own
			                   name, because a real letter is signed. Be direct and
			                   courteous and do not apologise at length. No bullet points,
			                   no headings, no restating the checklist, and never a number
			                   that was not given to you.
			confidence         CLEAR when the file is not close. BORDERLINE when it is, and
			                   another underwriter could reasonably land the other way.
			""";

	private final ChatClient chatClient;

	private final ChatClient followUpClient;

	private final ChatMemory chatMemory;

	/**
	 * Three advisors and the order between them is the architecture: chat memory outermost, then
	 * the context graph reading in, then the decision layer recording what came back. Each is a
	 * bean, and an agent that wants the context without the recording registers the first of the
	 * two and stops there.
	 */
	LoanOfficer(ChatClient.Builder builder, ChatModel chatModel, PrecedentAdvisor precedentAdvisor,
			DecisionTraceAdvisor decisionTraceAdvisor,
			Neo4jChatMemoryRepository chatMemoryRepository) {

		this.chatMemory = MessageWindowChatMemory.builder()
			.chatMemoryRepository(chatMemoryRepository)
			.build();

		this.chatClient = builder.defaultSystem(SYSTEM)
			.defaultAdvisors(MessageChatMemoryAdvisor.builder(this.chatMemory).build(),
					precedentAdvisor, decisionTraceAdvisor)
			.build();

		// A separate client for follow-up questions, built straight off the ChatModel rather than
		// mutated from this.chatClient: PrecedentAdvisor and DecisionTraceAdvisor are baked into
		// that one and cannot be un-registered, and both require the COMPANY_ID/REQUESTED_AMOUNT
		// params and file context that only the original decision provides. Chat memory is the one
		// advisor a follow-up needs, and it is the same instance, so it reads the transcript the
		// first client just wrote.
		this.followUpClient = ChatClient.builder(chatModel)
			.defaultSystem(SYSTEM)
			.defaultAdvisors(MessageChatMemoryAdvisor.builder(this.chatMemory).build())
			.build();
	}

	/**
	 * The conversation id is minted per call rather than held on this bean. One per run is what the
	 * demo wants, and a field would make that an accident of there being one run: every caller
	 * would share one conversation the moment anything called this twice.
	 */
	LoanAnswer answer(String companyId, long requestedAmount) {
		String question = "Can %s get a construction loan of $%,d?".formatted(companyId,
				requestedAmount);

		ChatClientResponse response = this.chatClient.prompt()
			.user(question)
			.advisors(advisor -> advisor
				.param(ChatMemory.CONVERSATION_ID, UUID.randomUUID().toString())
				.param(PrecedentAdvisor.COMPANY_ID, companyId)
				.param(PrecedentAdvisor.REQUESTED_AMOUNT, requestedAmount))
			.call()
			.chatClientResponse();

		return DecisionTraceAdvisor.answerIn(response);
	}

	/**
	 * Read back out of Neo4j, so what is printed is what chat memory actually stored. The id comes
	 * from the answer, which carries the one the run was decided under.
	 */
	List<Message> transcript(String conversationId) {
		return this.chatMemory.get(conversationId);
	}

	/**
	 * A second turn on the same conversation, with none of the file re-supplied. This is the whole
	 * point of chat memory: the verdict, the facts it was measured against, and the persona reading
	 * the file are not passed in here, they are read back out of Neo4j by
	 * {@link MessageChatMemoryAdvisor} under this id. An answer that makes sense is memory doing
	 * something; an answer that does not is memory doing nothing, which is the demo either way.
	 */
	String followUp(String conversationId, String question) {
		return this.followUpClient.prompt()
			.user(question)
			.advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
			.call()
			.content();
	}

}
