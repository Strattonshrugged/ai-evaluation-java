package io.github.strattonshrugged.aieval;

import java.util.ArrayList;
import java.util.List;

/**
 * A fully-resolved model to call: provider, model name, and optional reasoning
 * effort (low | medium | high | xhigh | max — only sent to providers/models
 * that support it; leave null otherwise).
 */
public record ModelSpec(Provider provider, String model, String effort) {

    /**
     * Parses {@code provider[:model[:effort]]}, filling in the provider's
     * default model when none is given. Empty segments count as omitted, so
     * {@code anthropic::max} means "default model, max effort".
     */
    public static ModelSpec parse(String spec) {
        String[] parts = spec.trim().split(":", -1);
        if (parts.length > 3) {
            throw new IllegalArgumentException("Expected provider[:model[:effort]], got \"" + spec + "\"");
        }
        Provider provider = Provider.fromId(parts[0]);
        String model = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : provider.defaultModel;
        String effort = parts.length > 2 && !parts[2].isBlank() ? parts[2].trim() : null;
        return new ModelSpec(provider, model, effort);
    }

    /** Parses a comma-separated list of specs, ignoring blank entries. */
    public static List<ModelSpec> parseList(String specs) {
        List<ModelSpec> result = new ArrayList<>();
        for (String s : specs.split(",")) {
            if (!s.isBlank()) {
                result.add(parse(s));
            }
        }
        return result;
    }

    @Override
    public String toString() {
        return provider.id() + "/" + model + (effort != null ? " (effort: " + effort + ")" : "");
    }
}
