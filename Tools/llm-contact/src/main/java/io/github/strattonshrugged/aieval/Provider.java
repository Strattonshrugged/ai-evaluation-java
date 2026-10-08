package io.github.strattonshrugged.aieval;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Every supported provider: its default endpoint and model, the env var
 * holding its API key, and how its API is spoken to. The single source of
 * truth for provider names, so the CLI's help text and validation can never
 * drift from what the client actually supports.
 */
public enum Provider {
    ANTHROPIC("https://api.anthropic.com/v1/messages", "claude-sonnet-5", "ANTHROPIC_API_KEY",
            Api.ANTHROPIC, null, null),
    // gpt-5.4-nano (and other newer OpenAI models) reject `max_tokens` with a 400,
    // demanding `max_completion_tokens` instead — the other OpenAI-compatible
    // providers below still accept `max_tokens`.
    OPENAI("https://api.openai.com/v1/chat/completions", "gpt-5.4-nano", "OPENAI_API_KEY",
            Api.OPENAI_COMPATIBLE, "max_completion_tokens", JsonMode.JSON_SCHEMA),
    GEMINI("https://generativelanguage.googleapis.com/v1beta/models", "gemini-3.5-flash", "GEMINI_API_KEY",
            Api.GEMINI, null, null),
    MISTRAL("https://api.mistral.ai/v1/chat/completions", "mistral-small-latest", "MISTRAL_API_KEY",
            Api.OPENAI_COMPATIBLE, "max_tokens", JsonMode.JSON_OBJECT),
    XAI("https://api.x.ai/v1/chat/completions", "grok-4.3", "X_API_KEY",
            Api.OPENAI_COMPATIBLE, "max_tokens", JsonMode.JSON_OBJECT),
    // deepseek-chat/deepseek-reasoner are deprecated 2026-07-24; deepseek-v4-flash is the replacement.
    DEEPSEEK("https://api.deepseek.com/chat/completions", "deepseek-v4-flash", "DEEPSEEK_API_KEY",
            Api.OPENAI_COMPATIBLE, "max_tokens", JsonMode.JSON_OBJECT);

    /** Wire protocol family. OpenAI, Mistral, xAI and DeepSeek all expose an OpenAI-style /chat/completions. */
    public enum Api { ANTHROPIC, OPENAI_COMPATIBLE, GEMINI }

    /**
     * Structured-output mechanism for OpenAI-compatible providers when a schema
     * is requested: JSON_SCHEMA has the API enforce the exact shape; JSON_OBJECT
     * only guarantees syntactically valid JSON, so callers still validate the shape.
     */
    public enum JsonMode { JSON_SCHEMA, JSON_OBJECT }

    public final String defaultSite;
    public final String defaultModel;
    public final String apiKeyEnv;
    public final Api api;
    public final String tokenParam;
    public final JsonMode jsonMode;

    Provider(String defaultSite, String defaultModel, String apiKeyEnv, Api api, String tokenParam, JsonMode jsonMode) {
        this.defaultSite = defaultSite;
        this.defaultModel = defaultModel;
        this.apiKeyEnv = apiKeyEnv;
        this.api = api;
        this.tokenParam = tokenParam;
        this.jsonMode = jsonMode;
    }

    /** Lower-case name as used on the command line, in suites, and in Run files. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Provider fromId(String id) {
        for (Provider p : values()) {
            if (p.id().equals(id.trim().toLowerCase(Locale.ROOT))) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown provider: \"" + id + "\". Known providers: " + knownIds());
    }

    public static String knownIds() {
        return Arrays.stream(values()).map(Provider::id).collect(Collectors.joining(", "));
    }
}
