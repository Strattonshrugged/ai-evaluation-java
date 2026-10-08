package io.github.strattonshrugged.aieval;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * One Run file: one suite executed against one target, graded by one or more
 * judges. Each judge's verdict is recorded independently — there's
 * deliberately no aggregate pass/fail here; evaluating a run as a whole is a
 * separate, later step.
 */
public record RunRecord(
        @JsonProperty("suite") String suite,
        @JsonProperty("suite_name") String suiteName,
        @JsonProperty("suite_description") String suiteDescription,
        @JsonProperty("target_provider") String targetProvider,
        @JsonProperty("target_model") String targetModel,
        @JsonProperty("target_site") String targetSite,
        @JsonProperty("target_effort") String targetEffort,
        @JsonProperty("judges") List<JudgeInfo> judges,
        @JsonProperty("timestamp") String timestamp,
        @JsonProperty("tests") List<TestResult> tests) {

    public record JudgeInfo(
            @JsonProperty("provider") String provider,
            @JsonProperty("model") String model,
            @JsonProperty("effort") String effort) {

        static JudgeInfo of(ModelSpec spec) {
            return new JudgeInfo(spec.provider().id(), spec.model(), spec.effort());
        }
    }

    /**
     * The target's reply to one test plus every judge's assessment of it.
     * {@code error} is set when the target call itself failed (network error,
     * provider 5xx, a reply with no usable text); {@code judgments} is then
     * empty since there was nothing to judge.
     */
    public record TestResult(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("prompt") String prompt,
            @JsonProperty("judgment_criteria") JsonNode judgmentCriteria,
            @JsonProperty("severity") String severity,
            @JsonProperty("max_tokens") int maxTokens,
            @JsonProperty("reply") String reply,
            @JsonProperty("error") String error,
            @JsonProperty("judgments") List<Judgment> judgments) {
    }

    /**
     * One judge's assessment of one reply. {@code verdict} is "pass", "fail",
     * or null — null with {@code judge_format_ok: false} means JSON was
     * requested and not delivered (check {@code reasoning}, which then holds
     * the judge's raw text); null with {@code error} set means the judge call
     * itself failed.
     */
    public record Judgment(
            @JsonProperty("judge_provider") String judgeProvider,
            @JsonProperty("judge_model") String judgeModel,
            @JsonProperty("judge_effort") String judgeEffort,
            @JsonProperty("verdict") String verdict,
            @JsonProperty("reasoning") String reasoning,
            @JsonProperty("judge_format_ok") Boolean judgeFormatOk,
            @JsonProperty("error") String error) {
    }
}
