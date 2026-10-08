package io.github.strattonshrugged.aieval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * An immutable test template loaded from {@code Suites/<id>.json}. Same schema
 * as AI-Evaluation-Portfolio's suites: suite-level metadata, an optional fixed
 * judge, and a list of tests.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Suite(
        @JsonProperty("suiteID") String suiteName,
        @JsonProperty("owasp_description") String description,
        @JsonProperty("judge_provider") String judgeProvider,
        @JsonProperty("judge_model") String judgeModel,
        @JsonProperty("judge_effort") String judgeEffort,
        @JsonProperty("tests") List<TestCase> tests) {

    /**
     * One test. {@code judgmentCriteria} is kept as raw JSON because it's either
     * a legacy plain string or a structured
     * {@code {pass_conditions[], fail_conditions[]?, severity_if_fail?}} object,
     * and it's copied into every Run verbatim.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TestCase(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("prompt") String prompt,
            @JsonProperty("judgment_criteria") JsonNode judgmentCriteria,
            @JsonProperty("max_tokens") Integer maxTokens) {

        public boolean isComplete() {
            return name != null && prompt != null && judgmentCriteria != null && !judgmentCriteria.isNull();
        }
    }

    /**
     * The suite's own declared judge, or null if it declares none. When present
     * it's authoritative — it replaces whatever judges were selected for the
     * run, so a suite's grading stays consistent no matter who runs it.
     */
    public ModelSpec declaredJudge() {
        if (judgeProvider == null || judgeProvider.isBlank()) {
            return null;
        }
        Provider provider = Provider.fromId(judgeProvider);
        String model = judgeModel != null && !judgeModel.isBlank() ? judgeModel : provider.defaultModel;
        return new ModelSpec(provider, model, judgeEffort != null && !judgeEffort.isBlank() ? judgeEffort : null);
    }

    public static Suite load(Path suitesDir, String id) throws IOException {
        Path path = suitesDir.resolve(id + ".json");
        if (!Files.exists(path)) {
            throw new IOException("Suite not found: " + path);
        }
        Suite suite = new ObjectMapper().readValue(path.toFile(), Suite.class);
        if (suite.tests() == null || suite.tests().isEmpty()) {
            throw new IOException("Suite \"" + id + "\" has no tests");
        }
        return suite;
    }

    /** Suite ids (filenames without .json) available in {@code Suites/}, sorted. */
    public static List<String> listIds(Path suitesDir) throws IOException {
        if (!Files.isDirectory(suitesDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(suitesDir)) {
            List<String> ids = new ArrayList<>(files
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - ".json".length()))
                    .toList());
            ids.sort(null);
            return ids;
        }
    }
}
