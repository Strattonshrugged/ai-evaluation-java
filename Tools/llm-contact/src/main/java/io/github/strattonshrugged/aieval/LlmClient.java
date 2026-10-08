package io.github.strattonshrugged.aieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Real HTTP implementation of {@link ModelCaller} for every {@link Provider}.
 * When a schema is passed, it's translated to that provider's best-fit
 * structured-output mechanism — but only Anthropic and OpenAI actually enforce
 * the exact shape, so callers must still validate what comes back.
 */
public final class LlmClient implements ModelCaller {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private final Config config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    public LlmClient(Config config) {
        this.config = config;
    }

    @Override
    public String call(ModelSpec model, int maxTokens, String prompt, ObjectNode schema) throws Exception {
        String apiKey = config.apiKey(model.provider());
        if (apiKey == null) {
            throw new IllegalStateException("Missing API key: " + model.provider().apiKeyEnv);
        }
        String site = config.site(model.provider());
        return switch (model.provider().api) {
            case ANTHROPIC -> callAnthropic(site, apiKey, model, maxTokens, prompt, schema);
            case OPENAI_COMPATIBLE -> callOpenAiCompatible(site, apiKey, model, maxTokens, prompt, schema);
            case GEMINI -> callGemini(site, apiKey, model, maxTokens, prompt, schema);
        };
    }

    private String callAnthropic(String site, String apiKey, ModelSpec model, int maxTokens, String prompt,
                                 ObjectNode schema) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model.model());
        body.put("max_tokens", maxTokens);
        body.set("messages", userMessages(prompt));

        ObjectNode outputConfig = MAPPER.createObjectNode();
        // Adaptive thinking + effort are only sent when explicitly requested,
        // since older/smaller models (e.g. Haiku 4.5) reject both with a 400.
        if (model.effort() != null) {
            body.putObject("thinking").put("type", "adaptive");
            outputConfig.put("effort", model.effort());
        }
        if (schema != null) {
            outputConfig.putObject("format").put("type", "json_schema").set("schema", schema);
        }
        if (!outputConfig.isEmpty()) {
            body.set("output_config", outputConfig);
        }

        JsonNode response = post(HttpRequest.newBuilder(URI.create(site))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01"), body);

        if ("refusal".equals(response.path("stop_reason").asText())) {
            String category = response.path("stop_details").path("category").asText("unspecified");
            return "[REFUSED by safety classifier - category: " + category + "]";
        }
        for (JsonNode block : response.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                return block.path("text").asText();
            }
        }
        throw new IllegalStateException("No text content in response: " + response);
    }

    private String callOpenAiCompatible(String site, String apiKey, ModelSpec model, int maxTokens, String prompt,
                                        ObjectNode schema) throws Exception {
        Provider provider = model.provider();
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model.model());
        body.put(provider.tokenParam, maxTokens);
        body.set("messages", userMessages(prompt));

        if (schema != null && provider.jsonMode == Provider.JsonMode.JSON_SCHEMA) {
            ObjectNode jsonSchema = body.putObject("response_format").put("type", "json_schema").putObject("json_schema");
            jsonSchema.put("name", "judgment").put("strict", true).set("schema", schema);
        } else if (schema != null) {
            // Older, more universally-supported JSON mode: guarantees valid JSON
            // syntax but not the schema's shape — the caller still validates that.
            body.putObject("response_format").put("type", "json_object");
        }

        JsonNode response = post(HttpRequest.newBuilder(URI.create(site))
                .header("Authorization", "Bearer " + apiKey), body);

        String content = response.path("choices").path(0).path("message").path("content").asText("");
        if (content.isEmpty()) {
            throw new IllegalStateException("No text content in response: " + response);
        }
        return content;
    }

    private String callGemini(String site, String apiKey, ModelSpec model, int maxTokens, String prompt,
                              ObjectNode schema) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.putArray("contents").addObject().putArray("parts").addObject().put("text", prompt);
        ObjectNode generationConfig = body.putObject("generationConfig");
        generationConfig.put("maxOutputTokens", maxTokens);
        if (schema != null) {
            generationConfig.put("responseMimeType", "application/json");
            // Gemini's schema format is an OpenAPI-3.0 subset — it doesn't recognize
            // additionalProperties, so strip it rather than risk a 400.
            ObjectNode geminiSchema = schema.deepCopy();
            geminiSchema.remove("additionalProperties");
            generationConfig.set("responseSchema", geminiSchema);
        }

        JsonNode response = post(HttpRequest.newBuilder(URI.create(site + "/" + model.model() + ":generateContent"))
                .header("x-goog-api-key", apiKey), body);

        String text = response.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
        if (text.isEmpty()) {
            throw new IllegalStateException("No text content in response: " + response);
        }
        return text;
    }

    private static ArrayNode userMessages(String prompt) {
        ArrayNode messages = MAPPER.createArrayNode();
        messages.addObject().put("role", "user").put("content", prompt);
        return messages;
    }

    private JsonNode post(HttpRequest.Builder request, ObjectNode body) throws Exception {
        HttpResponse<String> response = http.send(request
                        .header("Content-Type", "application/json")
                        .timeout(REQUEST_TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Request failed: " + response.statusCode() + "\n" + response.body());
        }
        return MAPPER.readTree(response.body());
    }
}
