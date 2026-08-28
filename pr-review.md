# PR Review: spring cleaning and bedrock

## Summary

This PR swaps the AI model provider from Anthropic to AWS Bedrock, using an OpenAI-compatible endpoint. It also changes how the app starts: instead of deciding a loan automatically on startup, the app now waits for a command. It adds a test setup that spins up a temporary Neo4j database, so tests do not need a real database connection. It also removes code that is no longer needed and cleans up the build file.

## Highlighted Changes

- **Provider switch**: The app now calls AWS Bedrock through an OpenAI-compatible API instead of calling Anthropic directly. `pom.xml`, `application.properties`, and `application.yaml` were updated to match.
- **New required setting**: The app now needs `AWS_BEARER_TOKEN_BEDROCK` instead of `ANTHROPIC_API_KEY`. This variable is checked in `run.sh` and `test-all-companies.sh`.
- **Command-based startup**: The old code ran a loan decision the moment the app started, using a `CommandLineRunner`. The new code moves this logic into a `DecisionCommands` class. The app now only decides a loan when the `decide` command runs. This means starting the app for a test no longer triggers a real model call by accident.
- **Spring Shell added**: The app can now run as an interactive shell. A user can type `decide C-1042 250000` directly instead of passing arguments at startup.
- **Seed loading moved to a bean**: Loading `seed.json` used to happen inside a static method on the `Seed` record. It now happens in a new `SeedConfig` class, which loads the file once at startup and shares it as a reusable component. Tests now get this data through injection instead of calling `Seed.load()` directly.
- **Thinking-mode code removed**: The old code turned off "adaptive thinking" for Anthropic's model and had to search through multiple response pieces to find the real answer. Since Bedrock's OpenAI-compatible endpoint does not produce this multi-piece output, this workaround code was deleted, and the matching tests were deleted too.
- **New Testcontainers setup**: A `TestcontainersConfiguration` class starts a temporary Neo4j database automatically when tests run. A `TestApplication` class lets a developer run the full app locally against this temporary database, without needing a real Neo4j connection string.
- **Tests updated to load Spring context**: `LoanGraphTests` and `PolicyEngineTests` previously built objects by hand. They now use `@SpringBootTest` to load a small Spring context and get their dependencies (the driver, the seed data) through autowiring.
- **New live model test**: `NativeStructuredOutputTests` replaces the deleted `DecisionTraceAdvisorTests` thinking-mode tests. It makes one real call to the configured model to confirm the response matches the expected JSON schema. It only runs when an API key is present.
- **Documentation updated**: `README.md` and `docs/reference.md` now describe the Bedrock setup instead of the Anthropic setup.

## Review and Feedback

- **The README lists the wrong model name.** `docs/reference.md` says the model is `openai.gpt-oss-120b`, but `application.properties` actually sets `spring.ai.openai.chat.model=xai.grok-4.6`. A reader who checks the docs will expect a different model than the one that actually runs. Fix the docs to match the real config, or update the config if the docs are correct.
- **`test-all-companies.sh` was not fully updated.** It still requires a `.env` file to exist and tells the user to copy `.env.example`. This PR deletes `.env.example` entirely. Running this script as written will send the user to a file that no longer exists. `run.sh` was changed to make `.env` optional, but `test-all-companies.sh` still hard-requires it. Update this script to match the new optional-`.env` approach, or restore a `.env.example` file if the project still wants one.
- **The Bedrock endpoint is hardcoded.** `spring.ai.openai.base-url` is set to a fixed URL in `application.properties`. If this URL ever changes, or if someone needs to point at a different region, they must edit code rather than set an environment variable. Consider making this configurable through an environment variable with the current value as the default.
- **Good call moving seed loading into a Spring bean.** This lets tests get the seed data through dependency injection instead of a static file read, which makes the test setup clearer and more consistent with how the rest of the app works.
- **Good call removing the thinking-mode workaround.** That code existed only to handle a quirk specific to the old Anthropic model. Removing it along with its tests keeps the codebase focused on what the current setup actually needs, rather than carrying logic for a scenario that no longer applies.
- **The new live model test is a reasonable safety net.** `NativeStructuredOutputTests` catches a real failure mode (the model not honoring the requested JSON schema) that mocked tests cannot catch. Gating it on the API key being present is the right call, since it avoids breaking the build for anyone without Bedrock access.
- **Consider a quick manual check before merging.** Since this PR is a full behavior change (different model provider and different startup mechanism), it is worth doing one real end-to-end run — `./run.sh` with a real token — to confirm the new command-based flow and the Bedrock connection work together in practice, not just in each test individually.
