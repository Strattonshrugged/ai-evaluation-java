package io.github.strattonshrugged.aieval;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sends a single prompt to a model and returns its visible text reply. An
 * interface so {@link SuiteRunner} can be exercised in tests without real API
 * calls (and without spending money).
 */
public interface ModelCaller {

    /**
     * @param model     which provider/model/effort to call
     * @param maxTokens per-call output token ceiling
     * @param prompt    single-turn user prompt
     * @param schema    JSON schema to request structured output against, or null for plain text
     * @throws RefusalException when the provider's safety layer blocked the call
     * @throws Exception on any other transport/provider failure, or a reply with no usable text
     */
    String call(ModelSpec model, int maxTokens, String prompt, ObjectNode schema) throws Exception;
}
