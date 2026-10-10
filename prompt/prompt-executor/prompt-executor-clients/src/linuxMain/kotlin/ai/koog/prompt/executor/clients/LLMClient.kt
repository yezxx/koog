@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package ai.koog.prompt.executor.clients

/**
 * Common interface for direct communication with LLM providers on Linux.
 * This interface defines methods for executing prompts and streaming responses.
 */
public actual abstract class LLMClient actual constructor() : LLMClientAPI, LLMEmbeddingProviderAPI
