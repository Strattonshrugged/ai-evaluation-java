package io.github.strattonshrugged.aieval;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Command(name = "aieval", mixinStandardHelpOptions = true, version = "aieval 1.0.0",
        description = "LLM evaluation harness: run Suites against target AIs, graded by judge AIs, recorded to Runs/.",
        subcommands = {Cli.RunCommand.class, Cli.ListCommand.class, Cli.TestConnectionsCommand.class,
                Cli.ContactCommand.class, Cli.GuiCommand.class},
        footer = {"", "Model specs are provider[:model[:effort]], e.g. anthropic:claude-sonnet-5:max.",
                "Known providers: anthropic, openai, gemini, mistral, xai, deepseek (see 'aieval list')."})
public final class Cli implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    public static void main(String[] args) {
        System.exit(new CommandLine(new Cli())
                .setCaseInsensitiveEnumValuesAllowed(true)
                // Bad input (unknown suite/provider, missing key, ...) gets a one-line
                // message rather than a stack trace.
                .setExecutionExceptionHandler((e, cmd, parseResult) -> {
                    cmd.getErr().println("Error: " + e.getMessage());
                    return 1;
                })
                .execute(args));
    }

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    @Command(name = "run", mixinStandardHelpOptions = true,
            description = "Run each selected suite against each selected target, graded by each selected judge. "
                    + "One Run file is written per suite x target. Any of --suites/--targets/--judges left off "
                    + "is asked for interactively (unless --no-prompt).")
    static final class RunCommand implements Callable<Integer> {

        @Option(names = {"-s", "--suites"}, paramLabel = "IDS",
                description = "Comma-separated suite ids (filenames in Suites/ without .json), or 'all'.")
        String suites;

        @Option(names = {"-t", "--targets"}, paramLabel = "SPECS",
                description = "Comma-separated models under test, provider[:model[:effort]].")
        String targets;

        @Option(names = {"-j", "--judges"}, paramLabel = "SPECS",
                description = "Comma-separated judge models, provider[:model[:effort]], or 'self' for each target "
                        + "to judge itself. A suite's own declared judge always replaces these.")
        String judges;

        @Option(names = "--max-tokens", paramLabel = "N",
                description = "Per-call token ceiling (default: MAX_TOKENS env/.env, else 1024). A test's own max_tokens still wins.")
        Integer maxTokens;

        @Option(names = "--dry-run", description = "Print the plan and how many API calls it would make, then stop.")
        boolean dryRun;

        @Option(names = "--no-prompt", description = "Never ask interactively; missing --suites/--targets is an error, missing --judges means self-judge.")
        boolean noPrompt;

        @Override
        public Integer call() throws Exception {
            PrintStream out = System.out;
            Config config = Config.load();
            List<String> available = Suite.listIds(config.suitesDir);
            InteractivePicker picker = new InteractivePicker(
                    new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), out);

            List<String> suiteIds;
            if (suites != null) {
                suiteIds = InteractivePicker.parseSuiteSelection(suites, available);
            } else if (noPrompt) {
                throw new CommandLine.ParameterException(new CommandLine(this), "Missing --suites");
            } else {
                suiteIds = picker.pickSuites(available);
            }

            List<ModelSpec> targetSpecs;
            if (targets != null) {
                targetSpecs = ModelSpec.parseList(targets);
            } else if (noPrompt) {
                throw new CommandLine.ParameterException(new CommandLine(this), "Missing --targets");
            } else {
                targetSpecs = picker.pickModels("Targets", false, null);
            }

            List<ModelSpec> judgeSpecs;
            if (judges != null) {
                judgeSpecs = judges.trim().equalsIgnoreCase("self") ? List.of() : ModelSpec.parseList(judges);
            } else if (noPrompt) {
                judgeSpecs = List.of();
            } else {
                judgeSpecs = picker.pickModels("Judges", true, "each target judges itself");
            }

            RunPlan plan = new RunPlan(config, suiteIds, targetSpecs, judgeSpecs,
                    maxTokens != null ? maxTokens : config.maxTokens());
            out.println();
            List<String> missing = plan.describe(out);
            if (dryRun) {
                return missing.isEmpty() ? 0 : 1;
            }
            if (!missing.isEmpty()) {
                System.err.println("Set the missing key(s) in Tools/llm-contact/.env or the environment.");
                return 1;
            }
            return plan.execute(new LlmClient(config), out, System.err).anyFailed() ? 1 : 0;
        }
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "List available suites and providers.")
    static final class ListCommand implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            Config config = Config.load();
            System.out.println("Suites (" + config.suitesDir + "):");
            for (String id : Suite.listIds(config.suitesDir)) {
                Suite suite = Suite.load(config.suitesDir, id);
                System.out.printf("  %-32s %2d tests%s%n", id, suite.tests().size(),
                        suite.declaredJudge() != null ? "  (declares judge " + suite.declaredJudge() + ")" : "");
            }
            System.out.println("\nProviders:");
            for (Provider p : Provider.values()) {
                System.out.printf("  %-10s default model: %-22s key: %s%s%n", p.id(), p.defaultModel, p.apiKeyEnv,
                        config.apiKey(p) == null ? " (not set)" : "");
            }
            return 0;
        }
    }

    @Command(name = "test-connections", mixinStandardHelpOptions = true,
            description = "Send a one-word prompt to every provider with an API key set; prints OK/FAIL/SKIPPED. "
                    + "Writes nothing to Runs/.")
    static final class TestConnectionsCommand implements Callable<Integer> {

        private static final String TEST_PROMPT = "Reply with exactly one word: OK";
        // Reasoning models (e.g. deepseek-v4-flash, gemini-3.5-flash) spend hidden
        // reasoning tokens before the visible answer, so a tight budget can come
        // back empty. 100 is an empirically-confirmed heuristic, not a bound.
        private static final int MAX_TOKENS = 100;

        private record Outcome(Provider provider, String status, long ms, String detail) {
        }

        @Override
        public Integer call() throws Exception {
            Config config = Config.load();
            LlmClient client = new LlmClient(config);
            System.out.println("Testing API connections (no Runs/ record written)\n");

            List<Outcome> outcomes = new ArrayList<>();
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<Outcome>> futures = new ArrayList<>();
                for (Provider p : Provider.values()) {
                    futures.add(pool.submit(() -> test(config, client, p)));
                }
                for (Future<Outcome> f : futures) {
                    outcomes.add(f.get());
                }
            }

            int tested = 0;
            int failed = 0;
            for (Outcome o : outcomes) {
                String label = String.format("%-10s %-22s", o.provider.id(), o.provider.defaultModel);
                switch (o.status) {
                    case "ok" -> System.out.printf("OK      %s %dms  \"%s\"%n", label, o.ms, o.detail);
                    case "fail" -> System.out.printf("FAIL    %s %dms  %s%n", label, o.ms, o.detail);
                    default -> System.out.printf("SKIPPED %s (%s)%n", label, o.detail);
                }
                if (!o.status.equals("skipped")) {
                    tested++;
                }
                if (o.status.equals("fail")) {
                    failed++;
                }
            }
            System.out.printf("%n%d/%d configured provider(s) OK%n", tested - failed, tested);
            return failed > 0 ? 1 : 0;
        }

        private static Outcome test(Config config, LlmClient client, Provider p) {
            if (config.apiKey(p) == null) {
                return new Outcome(p, "skipped", 0, p.apiKeyEnv + " not set");
            }
            long start = System.currentTimeMillis();
            try {
                String reply = client.call(new ModelSpec(p, p.defaultModel, null), MAX_TOKENS, TEST_PROMPT, null);
                String shown = reply.trim().replaceAll("\\s+", " ");
                return new Outcome(p, "ok", System.currentTimeMillis() - start, shown.substring(0, Math.min(60, shown.length())));
            } catch (Exception e) {
                String message = String.valueOf(e.getMessage()).split("\n")[0];
                return new Outcome(p, "fail", System.currentTimeMillis() - start, message);
            }
        }
    }

    @Command(name = "contact", mixinStandardHelpOptions = true,
            description = "Send one ad-hoc prompt to one model and print the reply. Writes nothing to Runs/.")
    static final class ContactCommand implements Callable<Integer> {

        @Parameters(index = "0", paramLabel = "TARGET", description = "provider[:model[:effort]]")
        String target;

        @Parameters(index = "1..*", paramLabel = "PROMPT", description = "The prompt (remaining words are joined with spaces).")
        List<String> prompt;

        @Option(names = "--max-tokens", paramLabel = "N", description = "Token ceiling (default: MAX_TOKENS env/.env, else 1024).")
        Integer maxTokens;

        @Override
        public Integer call() throws Exception {
            Config config = Config.load();
            ModelSpec spec = ModelSpec.parse(target);
            String text = String.join(" ", prompt);
            String reply = new LlmClient(config).call(spec, maxTokens != null ? maxTokens : config.maxTokens(), text, null);
            System.out.println("Target: " + spec);
            System.out.println("Prompt: " + text);
            System.out.println("Response: " + reply);
            return 0;
        }
    }

    @Command(name = "gui", mixinStandardHelpOptions = true,
            description = "Open a window to pick suites, target AIs and judge AIs, then watch the run's progress. "
                    + "Closing the window exits.")
    static final class GuiCommand implements Callable<Integer> {

        @Option(names = "--max-tokens", paramLabel = "N",
                description = "Per-call token ceiling (default: MAX_TOKENS env/.env, else 1024). A test's own max_tokens still wins.")
        Integer maxTokens;

        @Override
        public Integer call() throws Exception {
            Config config = Config.load();
            return WizardGui.show(config, maxTokens != null ? maxTokens : config.maxTokens());
        }
    }
}
