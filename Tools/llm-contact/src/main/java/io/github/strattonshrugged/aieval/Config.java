package io.github.strattonshrugged.aieval;

import io.github.cdimascio.dotenv.Dotenv;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Repo locations plus settings read from real environment variables, falling
 * back to {@code Tools/llm-contact/.env}.
 */
public final class Config {

    public static final int DEFAULT_MAX_TOKENS = 1024;

    public final Path repoRoot;
    public final Path suitesDir;
    public final Path runsDir;
    private final Dotenv env;

    private Config(Path repoRoot) {
        this.repoRoot = repoRoot;
        this.suitesDir = repoRoot.resolve("Suites");
        this.runsDir = repoRoot.resolve("Runs");
        this.env = Dotenv.configure()
                .directory(repoRoot.resolve("Tools").resolve("llm-contact").toString())
                .ignoreIfMissing()
                .load();
    }

    /**
     * Finds the repo root by walking up from the working directory until a
     * folder containing both {@code Suites/} and {@code Tools/} turns up, so
     * the harness works whether launched from the repo root, from
     * {@code Tools/llm-contact} (as {@code gradlew run} does), or anywhere below.
     */
    public static Config load() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("Suites")) && Files.isDirectory(dir.resolve("Tools"))) {
                return new Config(dir);
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not find the repo root (a folder containing Suites/ and Tools/) "
                + "above " + Path.of("").toAbsolutePath());
    }

    /** Real environment variable first, then .env; null when unset or blank. */
    public String get(String name) {
        String value = env.get(name);
        return value == null || value.isBlank() ? null : value;
    }

    public String apiKey(Provider provider) {
        return get(provider.apiKeyEnv);
    }

    /** Optional endpoint override per provider, e.g. ANTHROPIC_SITE. */
    public String site(Provider provider) {
        String override = get(provider.name() + "_SITE");
        return override != null ? override : provider.defaultSite;
    }

    public int maxTokens() {
        String value = get("MAX_TOKENS");
        if (value == null) {
            return DEFAULT_MAX_TOKENS;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("MAX_TOKENS must be an integer, got \"" + value + "\"");
        }
    }
}
