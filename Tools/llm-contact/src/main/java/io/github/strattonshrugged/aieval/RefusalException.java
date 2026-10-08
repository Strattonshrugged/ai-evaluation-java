package io.github.strattonshrugged.aieval;

/**
 * The provider's own safety layer blocked the call, reported through a
 * structured API field (Anthropic stop_reason "refusal", OpenAI-style
 * finish_reason "content_filter" or message.refusal, Gemini block/finish
 * reasons). Not thrown for a model that simply declines in its reply text —
 * that's an ordinary reply for the judges to grade.
 */
public final class RefusalException extends Exception {

    private final String category;

    public RefusalException(String category) {
        super("Refused by provider safety filter (category: " + category + ")");
        this.category = category;
    }

    /** The provider's reason for the block, as it reported it, or "unspecified". */
    public String category() {
        return category;
    }
}
