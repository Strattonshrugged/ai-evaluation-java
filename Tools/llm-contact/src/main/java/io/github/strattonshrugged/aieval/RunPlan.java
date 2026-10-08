package io.github.strattonshrugged.aieval;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A full selection — suites x targets, graded by judges — shared by the CLI
 * and the GUI so both describe and execute a run identically.
 */
public final class RunPlan {

    private final Config config;
    private final Map<String, Suite> suites;
    private final List<ModelSpec> targets;
    private final List<ModelSpec> judges;
    private final int maxTokens;

    /** @param judges empty means each target judges itself */
    public RunPlan(Config config, List<String> suiteIds, List<ModelSpec> targets, List<ModelSpec> judges,
                   int maxTokens) throws IOException {
        if (suiteIds.isEmpty()) {
            throw new IllegalArgumentException("No suites selected");
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("No targets selected");
        }
        this.config = config;
        this.suites = new LinkedHashMap<>();
        for (String id : suiteIds) {
            suites.put(id, Suite.load(config.suitesDir, id));
        }
        this.targets = List.copyOf(targets);
        this.judges = List.copyOf(judges);
        this.maxTokens = maxTokens;
    }

    /**
     * Prints each suite x target pairing with its resolved judges and the total
     * API call count; returns the env var names of any API keys still missing.
     */
    public List<String> describe(PrintStream out) {
        out.println("Plan:");
        int calls = 0;
        Set<Provider> needed = new LinkedHashSet<>();
        for (Map.Entry<String, Suite> entry : suites.entrySet()) {
            int tests = entry.getValue().tests().size();
            for (ModelSpec target : targets) {
                List<ModelSpec> resolved = SuiteRunner.resolveJudges(entry.getValue(), target, judges);
                calls += tests * (1 + resolved.size());
                needed.add(target.provider());
                resolved.forEach(j -> needed.add(j.provider()));
                out.printf("  %s x %s, judged by %s%n", entry.getKey(), target,
                        resolved.stream().map(ModelSpec::toString).toList());
            }
            if (entry.getValue().declaredJudge() != null && !judges.isEmpty()) {
                out.printf("  (note: %s declares its own judge, which replaces the selected judges)%n", entry.getKey());
            }
        }
        out.printf("  %d API call(s), max %d tokens each unless a test overrides it%n", calls, maxTokens);

        List<String> missing = needed.stream().filter(p -> config.apiKey(p) == null).map(p -> p.apiKeyEnv).toList();
        if (!missing.isEmpty()) {
            out.println("  Missing API key(s): " + String.join(", ", missing));
        }
        return missing;
    }

    /** Result of {@link #execute}: Run files written, and whether any suite x target failed outright. */
    public record Outcome(List<Path> written, boolean anyFailed) {
    }

    public Outcome execute(ModelCaller caller, PrintStream out, PrintStream err) {
        SuiteRunner runner = new SuiteRunner(caller, maxTokens, out);
        List<Path> written = new ArrayList<>();
        boolean anyFailed = false;
        for (Map.Entry<String, Suite> entry : suites.entrySet()) {
            for (ModelSpec target : targets) {
                out.println();
                List<ModelSpec> resolved = SuiteRunner.resolveJudges(entry.getValue(), target, judges);
                try {
                    RunRecord run = runner.run(entry.getKey(), entry.getValue(), target,
                            config.site(target.provider()), resolved);
                    Path path = SuiteRunner.write(config.runsDir, run);
                    written.add(path);
                    out.println("  Saved: " + config.repoRoot.relativize(path));
                } catch (Exception e) {
                    // One suite x target going wrong shouldn't cost the rest of the plan.
                    err.printf("  Failed %s x %s: %s%n", entry.getKey(), target, e.getMessage());
                    anyFailed = true;
                }
            }
        }
        out.printf("%nDone: %d Run file(s) written to %s%n", written.size(), config.repoRoot.relativize(config.runsDir));
        return new Outcome(written, anyFailed);
    }
}
