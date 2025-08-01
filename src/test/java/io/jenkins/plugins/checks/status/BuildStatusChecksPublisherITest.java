package io.jenkins.plugins.checks.status;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import static org.assertj.core.api.Assertions.assertThat;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jvnet.hudson.test.TestExtension;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Functions;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.tasks.BatchFile;
import hudson.tasks.Shell;
import io.jenkins.plugins.checks.api.ChecksConclusion;
import io.jenkins.plugins.checks.api.ChecksDetails;
import io.jenkins.plugins.checks.api.ChecksPublisherFactory;
import io.jenkins.plugins.checks.api.ChecksStatus;
import io.jenkins.plugins.checks.util.CapturingChecksPublisher;
import io.jenkins.plugins.util.IntegrationTestWithJenkinsPerTest;

/**
 * Tests that the {@link BuildStatusChecksPublisher} listens to the status of a {@link Run} and publishes status
 * accordingly.
 */
@SuppressWarnings({"PMD.AddEmptyString", "checkstyle:LambdaBodyLength"})
@SuppressFBWarnings("NP_NONNULL_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR")
class BuildStatusChecksPublisherITest extends IntegrationTestWithJenkinsPerTest {
    private CapturingChecksPublisher.Factory getFactory() {
        return getJenkins().getInstance().getExtensionList(ChecksPublisherFactory.class)
                .stream()
                .filter(f -> f instanceof CapturingChecksPublisher.Factory)
                .map(f -> (CapturingChecksPublisher.Factory) f)
                .findAny()
                .orElseThrow(() -> new AssertionError("No CapturingChecksPublisher registered as @TestExtension?"));
    }

    /**
     * Clean captured checks between tests.
     */
    @AfterEach
    public void clearChecks() {
        getFactory().getPublishedChecks().clear();
    }

    private ChecksProperties getProperties() {
        return getJenkins().getInstance().getExtensionList(ChecksProperties.class)
                .stream()
                .findAny()
                .orElseThrow(() -> new AssertionError("No ChecksProperties registered as @TestExtension?"));
    }

    /**
     * Tests when the implementation of {@link AbstractStatusChecksProperties} is not applicable,
     * a status checks should not be published.
     */
    @Test
    public void shouldNotPublishStatusWhenNotApplicable() {
        getProperties().setApplicable(false);

        buildSuccessfully(createFreeStyleProject());

        assertThat(getFactory().getPublishedChecks()).hasSize(0);
    }

    /**
     * Tests when status checks is skipped, a status checks should not be published.
     */
    @Test
    public void shouldNotPublishStatusWhenSkipped() {
        getProperties().setApplicable(true);
        getProperties().setSkipped(true);
        getProperties().setName("Test Status");

        buildSuccessfully(createFreeStyleProject());

        assertThat(getFactory().getPublishedChecks()).hasSize(0);
    }

    /**
     * Tests when an implementation of {@link AbstractStatusChecksProperties} is applicable and not skipped,
     * a status checks using the specified name should be published.
     */
    @Test
    public void shouldPublishStatusWithProperties() throws Exception {
        getProperties().setApplicable(true);
        getProperties().setSkipped(false);
        getProperties().setName("Test Status");

        buildSuccessfully(createFreeStyleProject());
        // Wait for the job to finish to work around slow Windows builds sometimes
        this.getJenkins().waitUntilNoActivity();
        assertThat(getFactory().getPublishedChecks()).hasSize(3);

        ChecksDetails details = getFactory().getPublishedChecks().get(0);

        assertThat(details.getName()).contains("Test Status");
        assertThat(details.getStatus()).isEqualTo(ChecksStatus.QUEUED);
        assertThat(details.getConclusion()).isEqualTo(ChecksConclusion.NONE);

        details = getFactory().getPublishedChecks().get(1);

        assertThat(details.getName()).contains("Test Status");
        assertThat(details.getStatus()).isEqualTo(ChecksStatus.IN_PROGRESS);
        assertThat(details.getConclusion()).isEqualTo(ChecksConclusion.NONE);

        details = getFactory().getPublishedChecks().get(2);

        assertThat(details.getName()).contains("Test Status");
        assertThat(details.getStatus()).isEqualTo(ChecksStatus.COMPLETED);
        assertThat(details.getConclusion()).isEqualTo(ChecksConclusion.SUCCESS);
    }

    /**
     * Test checks output includes pipeline details.
     */
    @Test
    public void shouldPublishStageDetails() throws Exception {
        getProperties().setApplicable(true);
        getProperties().setSkipped(false);
        getProperties().setSuppressLogs(false);
        getProperties().setName("Test Status");
        WorkflowJob job = createPipeline();

        job.setDefinition(new CpsFlowDefinition(""
                + "node {\n"
                + "  stage('Simple Stage') {\n"
                + "  }\n"
                + "  stage('In parallel') {\n"
                + "    parallel 'p1': {\n"
                + "      stage('p1s1') {\n"
                + "        unstable('something went wrong')\n"
                + "      }\n"
                + "      stage('p1s2') {\n"
                + "      }\n"
                + "    }, 'p2': {}\n"
                + "  }\n"
                + "  stage('Fails') {\n"
                + "    error('a fatal error occurs')\n"
                + "  }\n"
                + "}", true));

        buildWithResult(job, Result.FAILURE);

        List<ChecksDetails> checksDetails = getFactory().getPublishedChecks();

        assertThat(checksDetails).hasSize(9);

        // Details 0, queued
        ChecksDetails details = checksDetails.get(0);
        assertThat(details.getStatus()).isEqualTo(ChecksStatus.QUEUED);
        assertThat(details.getConclusion()).isEqualTo(ChecksConclusion.NONE);
        assertThat(details.getName()).contains("Test Status");
        assertThat(details.getOutput()).isNotPresent();

        // Details 1, first stage
        details = checksDetails.get(1);
        assertThat(details.getStatus()).isEqualTo(ChecksStatus.IN_PROGRESS);
        assertThat(details.getConclusion()).isEqualTo(ChecksConclusion.NONE);
        assertThat(details.getOutput()).isPresent().get().satisfies(output -> {
            assertThat(output.getTitle()).contains("In progress");
            assertThat(output.getSummary()).isPresent().get().satisfies(StringUtils::isBlank);
            assertThat(output.getText()).isPresent().get().asString().contains("* Simple Stage *(running)*");
        });

        // Details 2, first stage finished, parallel started
        details = checksDetails.get(2);
        assertThat(details.getOutput()).isPresent().get().satisfies(output -> {
            assertThat(output.getTitle()).contains("In progress");
            assertThat(output.getSummary()).isPresent().get().satisfies(StringUtils::isBlank);
            assertThat(output.getText()).isPresent().get().satisfies(text -> {
                assertThat(text).matches(Pattern.compile(".*\\* Simple Stage \\*\\([^)]+\\)\\*.*", Pattern.DOTALL));
                assertThat(text).contains("  * In parallel *(running)*");
            });
        });

        // Details 6, p1s1 has finished and emitted unstable
        details = checksDetails.get(6);
        assertThat(details.getOutput()).isPresent().get().satisfies(output -> {
            assertThat(output.getTitle()).contains("In parallel/p1/p1s1: warning in 'unstable' step");
            assertThat(output.getSummary()).isPresent().get().asString().isEqualToIgnoringNewLines(""
                    + "### `In parallel / p1 / p1s1 / Set stage result to unstable`\n"
                    + "Warning in `unstable` step, with arguments `something went wrong`.\n"
                    + "```\n"
                    + "something went wrong\n"
                    + "```\n"
                    + "\n");
            assertThat(output.getText()).isPresent().get().asString().matches(Pattern.compile(".*"
                    + "  \\* Simple Stage \\*\\([^)]+\\)\\*\\s+"
                    + "  \\* In parallel \\*\\(running\\)\\*\\s+"
                    + "    \\* p1 \\*\\(running\\)\\*\\s+"
                    + "      \\* p1s1 \\*\\([^)]+\\)\\*\\s+"
                    + "        \\*\\*Unstable\\*\\*: \\*something went wrong\\* - \\[logs\\]\\([^)]+\\)\\s+"
                    + "      \\* p1s2 \\*\\(running\\)\\*\\s+"
                    + "    \\* p2 \\*\\([^)]+\\)\\*\\s+.*", Pattern.DOTALL));
        });

        // Details 8, final checks
        details = checksDetails.get(8);
        assertThat(details.getStatus()).isEqualTo(ChecksStatus.COMPLETED);
        assertThat(details.getConclusion()).isEqualTo(ChecksConclusion.FAILURE);
        assertThat(details.getOutput()).isPresent().get().satisfies(output -> {
            assertThat(output.getTitle()).contains("Fails: error in 'error' step");
            assertThat(output.getSummary()).isPresent().get().asString().matches(Pattern.compile(".*"
                    + "### `In parallel / p1 / p1s1 / Set stage result to unstable`\\s+"
                    + "Warning in `unstable` step, with arguments `something went wrong`\\.\\s+"
                    + "```\\s+"
                    + "something went wrong\\s+"
                    + "```\\s+"
                    + "### `Fails / Error signal`\\s+"
                    + "Error in `error` step, with arguments `a fatal error occurs`\\.\\s+"
                    + "```\\s+"
                    + "a fatal error occurs\\s+"
                    + "```\\s+", Pattern.DOTALL));
            assertThat(output.getText()).isPresent().get().asString().matches(Pattern.compile(".*"
                            + "  \\* Simple Stage \\*\\([^)]+\\)\\*\\s+"
                            + "  \\* In parallel \\*\\([^)]+\\)\\*\\s+"
                            + "    \\* p1 \\*\\([^)]+\\)\\*\\s+"
                            + "      \\* p1s1 \\*\\([^)]+\\)\\*\\s+"
                            + "        \\*\\*Unstable\\*\\*: \\*something went wrong\\* - \\[logs\\]\\([^)]+\\)\\s+"
                            + "      \\* p1s2 \\*\\([^)]+\\)\\*\\s+"
                            + "    \\* p2 \\*\\([^)]+\\)\\*\\s+"
                            + "  \\* Fails \\*\\([^)]+\\)\\*\\s+"
                            + "    \\*\\*Error\\*\\*: \\*a fatal error occurs\\*\\s+.*",
                    Pattern.DOTALL));
        });
    }

    /**
     * Test checks output includes pipeline details, but not logs, when requested.
     */
    @Test
    public void shouldPublishStageDetailsWithoutLogsIfRequested() throws Exception {
        getProperties().setApplicable(true);
        getProperties().setSkipped(false);
        getProperties().setName("Test Status");
        getProperties().setSuppressLogs(true);
        WorkflowJob job = createPipeline();

        job.setDefinition(new CpsFlowDefinition(""
                + "node {\n"
                + "  stage('Simple Stage') {\n"
                + "  }\n"
                + "  stage('In parallel') {\n"
                + "    parallel 'p1': {\n"
                + "      stage('p1s1') {\n"
                + "        unstable('something went wrong')\n"
                + "      }\n"
                + "      stage('p1s2') {\n"
                + "      }\n"
                + "    }, 'p2': {}\n"
                + "  }\n"
                + "  stage('Fails') {\n"
                + "    archiveArtifacts artifacts: 'oh dear', fingerprint: true\n"
                + "  }\n"
                + "}", true));

        buildWithResult(job, Result.FAILURE);

        List<ChecksDetails> checksDetails = getFactory().getPublishedChecks();

        assertThat(checksDetails).hasSize(9);

        ChecksDetails details = checksDetails.get(8);
        assertThat(details.getStatus()).isEqualTo(ChecksStatus.COMPLETED);
        assertThat(details.getConclusion()).isEqualTo(ChecksConclusion.FAILURE);
        assertThat(details.getOutput()).isPresent().get().satisfies(output -> {
            assertThat(output.getTitle()).contains("Fails: error in 'archiveArtifacts' step");
            assertThat(output.getSummary()).isPresent().get().asString().matches(Pattern.compile(".*"
                    + "### `In parallel / p1 / p1s1 / Set stage result to unstable`\\s+"
                    + "Warning in `unstable` step, with arguments `something went wrong`\\.\\s+"
                    + "```\\s+"
                    + "something went wrong\\s+"
                    + "```\\s+"
                    + "### `Fails / Archive the artifacts`\\s+"
                    + "Error in `archiveArtifacts` step\\.\\s+"
                    + "```\\s+"
                    + "No artifacts found that match the file pattern \"oh dear\"\\. Configuration error\\?\\s+"
                    + "```\\s+", Pattern.DOTALL));
            assertThat(output.getText()).isPresent().asString().matches(Pattern.compile(".*"
                            + "  \\* Simple Stage \\*\\([^)]+\\)\\*\\s+"
                            + "  \\* In parallel \\*\\([^)]+\\)\\*\\s+"
                            + "    \\* p1 \\*\\([^)]+\\)\\*\\s+"
                            + "      \\* p1s1 \\*\\([^)]+\\)\\*\\s+"
                            + "        \\*\\*Unstable\\*\\*: \\*something went wrong\\* - \\[logs\\]\\([^)]+\\)\\s+"
                            + "      \\* p1s2 \\*\\([^)]+\\)\\*\\s+"
                            + "    \\* p2 \\*\\([^)]+\\)\\*\\s+"
                            + "  \\* Fails \\*\\([^)]+\\)\\*\\s+"
                            + "    \\*\\*Error\\*\\*: \\*No artifacts found that match the file pattern \"oh dear\". Configuration error\\?\\* - \\[logs\\]\\([^)]+\\)\\s+.*",
                    Pattern.DOTALL));
        });
    }

    /**
     * Test that log messages are properly truncated when they exceed the maximum size limit.
     */
    @Test
    public void shouldTruncateLogsWhenExceedingMaxSize() throws Exception {
        getProperties().setApplicable(true);
        getProperties().setSkipped(false);
        getProperties().setName("Test Status");
        getProperties().setSuppressLogs(false);
        WorkflowJob job = createPipeline();

        // Create a pipeline that generates a large log output
        job.setDefinition(new CpsFlowDefinition(""
                + "node {\n"
                + "  stage('Large Log Stage') {\n"
                + "    // Generate a large log using Jenkins' built-in commands\n"
                + "    def logContent = (1..1000).collect { i ->\n"
                + "      \"Line ${i}: This is a very long log line that will be repeated many times to test truncation. Adding some extra system information here.\"\n"
                + "    }.join('\\n')\n"
                + "    // Use writeFile and bat/sh based on platform\n"
                + "    writeFile file: 'large_log.txt', text: logContent\n"
                + "    if (isUnix()) {\n"
                + "      sh 'cat large_log.txt && exit 1'\n"
                + "    } else {\n"
                + "      bat 'type large_log.txt && exit /b 1'\n"
                + "    }\n"
                + "    error('Pipeline failed with large logs')\n"
                + "  }\n"
                + "}", true));

        buildWithResult(job, Result.FAILURE);

        List<ChecksDetails> checksDetails = getFactory().getPublishedChecks();
        
        // Get the final check details which should contain the truncated logs
        ChecksDetails details = checksDetails.get(checksDetails.size() - 1);
        assertThat(details.getStatus()).isEqualTo(ChecksStatus.COMPLETED);
        assertThat(details.getConclusion()).isEqualTo(ChecksConclusion.FAILURE);
        assertThat(details.getOutput()).isPresent().get().satisfies(output -> {
            assertThat(output.getSummary()).isPresent().get().satisfies(summary -> {
                // Verify the log section exists and is truncated
                assertThat(summary).contains("<details>");
                assertThat(summary).contains("</details>");
                assertThat(summary).contains("Build log");
                assertThat(summary).contains("Build log truncated.");
                assertThat(summary).doesNotContain("Line 1:");  // Should be truncated from the start
                assertThat(summary).contains("exit");  // Should see the exit command at the end
                // Verify the truncation message appears at the start of the log section
                assertThat(summary).matches(Pattern.compile(".*<summary>Build log</summary>\\s+\\n```\\s*\\nBuild log truncated.\\n\\n.*", Pattern.DOTALL));
                // Verify the total size is within limits
                assertThat(summary.length()).isLessThanOrEqualTo(65_535);
            });
        });
    }

    /**
     * Validates that a simple successful pipeline works.
     */
    @Test
    public void shouldPublishSimplePipeline() throws Exception {
        getProperties().setApplicable(true);
        getProperties().setSkipped(false);
        getProperties().setName("Test Status");
        WorkflowJob job = createPipeline();

        job.setDefinition(new CpsFlowDefinition(""
                + "node {\n"
                + "  echo 'Hello, world'"
                + "}", true));

        buildWithResult(job, Result.SUCCESS);

        List<ChecksDetails> checksDetails = getFactory().getPublishedChecks();

        ChecksDetails details = checksDetails.get(1);
        assertThat(details.getOutput()).isPresent().get().satisfies(output -> assertThat(output.getTitle()).contains("Success"));
    }

    /**
     * Tests that FreeStyleBuildAnalyzer publishes build log output in checks.
     */
    @Test
    public void shouldPublishFreeStyleBuildLog() throws Exception {
        getProperties().setApplicable(true);
        getProperties().setSkipped(false);
        getProperties().setSuppressLogs(false);
        getProperties().setName("FreeStyle Status");

        // Create a FreeStyle project and add a build step
        var project = createFreeStyleProject();
        if (Functions.isWindows()) {
            project.getBuildersList().add(new BatchFile("echo hello from windows"));
        }
        else {
            project.getBuildersList().add(new Shell("echo hello from unix"));
        }

        buildSuccessfully(project);
        this.getJenkins().waitUntilNoActivity();
        List<ChecksDetails> checksDetails = getFactory().getPublishedChecks();
        // The last check should contain the build log
        ChecksDetails details = checksDetails.get(checksDetails.size() - 1);
        assertThat(details.getName()).contains("FreeStyle Status");
        assertThat(details.getStatus()).isEqualTo(ChecksStatus.COMPLETED);
        assertThat(details.getConclusion()).isEqualTo(ChecksConclusion.SUCCESS);
        assertThat(details.getOutput()).isPresent().get().satisfies(output -> {
            assertThat(output.getSummary()).isPresent();
            assertThat(output.getSummary().get()).contains("Build Log");
            assertThat(output.getSummary().get()).contains("hello from");
        });
    }

    /**
     * Tests that FreeStyleBuildAnalyzer suppresses logs when requested.
     */
    @Test
    public void shouldSuppressFreeStyleBuildLog() throws Exception {
        getProperties().setApplicable(true);
        getProperties().setSkipped(false);
        getProperties().setSuppressLogs(true);
        getProperties().setName("FreeStyle Status");

        var project = createFreeStyleProject();
        if (Functions.isWindows()) {
            project.getBuildersList().add(new BatchFile("echo hello from windows"));
        }
        else {
            project.getBuildersList().add(new Shell("echo hello from unix"));
        }

        buildSuccessfully(project);
        this.getJenkins().waitUntilNoActivity();
        List<ChecksDetails> checksDetails = getFactory().getPublishedChecks();
        ChecksDetails details = checksDetails.get(checksDetails.size() - 1);
        assertThat(details.getOutput()).isPresent().get().satisfies(output -> {
            assertThat(output.getSummary()).isNotPresent();
        });
    }

    @SuppressFBWarnings(value = "UPM_UNCALLED_PRIVATE_METHOD", justification = "Called by JUnit")
    private static Stream<Arguments> freeStyleBuildLogParameters() {
        return Stream.of(
                Arguments.of(characterTruncationLog()),  // Truncate by lines
                Arguments.of(lineTruncationLog())  // Truncate by character length
        );
    }

    /**
     * Tests that FreeStyleBuildAnalyzer truncates logs based on the number of lines or character length.
     * @param log the log content to be tested for truncation
     */
    @ParameterizedTest
    @MethodSource("freeStyleBuildLogParameters")
    public void shouldTruncateFreeStyleBuildLog(final String log) throws Exception {
        getProperties().setApplicable(true);
        getProperties().setSkipped(false);
        getProperties().setSuppressLogs(false);
        getProperties().setName("FreeStyle Status");

        var project = createFreeStyleProject();
        if (Functions.isWindows()) {
            project.getBuildersList().add(new BatchFile(log));
        }
        else {
            project.getBuildersList().add(new Shell(log));
        }

        buildSuccessfully(project);
        this.getJenkins().waitUntilNoActivity();
        List<ChecksDetails> checksDetails = getFactory().getPublishedChecks();
        ChecksDetails details = checksDetails.get(checksDetails.size() - 1);
        assertThat(details.getOutput()).isPresent().get().satisfies(output -> {
            assertThat(output.getSummary()).isPresent().get().satisfies(summary -> {
                // Verify the log section exists and is truncated
                assertThat(summary).contains("<details>");
                assertThat(summary).contains("</details>");
                assertThat(summary).contains("Build log");
                assertThat(summary).contains("Build log truncated.");
                assertThat(summary).doesNotContain("Line 1:");  // Should be truncated from the start
                // Verify the truncation message appears at the start of the log section
                assertThat(summary).matches(Pattern.compile(".*<summary>Build Log</summary>\\s+\\n```\\s*\\nBuild log truncated.\\n\\n.*", Pattern.DOTALL));
                // Verify the total size is within limits
                assertThat(summary.length()).isLessThanOrEqualTo(65_535);
            });
        });
    }

    private static String characterTruncationLog() {
        int logLines = 1000;
        String logSuffix = "This is a very long log line that will be repeated many times to test truncation. Adding some extra system information here.";
        StringBuilder script = new StringBuilder(logLines * logSuffix.length() * 4);
        for (int i = 0; i < logLines; i++) {
            script.append("echo \"Line ").append(i).append(": ").append(logSuffix).append(logSuffix).append(logSuffix).append("\"\n");
        }
        return script.toString();
    }

    private static String lineTruncationLog() {
        int logLines = 2000;
        StringBuilder script = new StringBuilder(logLines * 100);
        for (int i = 0; i < logLines; i++) {
            script.append("echo \"Line ").append(i).append(": This is a test log line\"\n");
        }
        return script.toString();
    }

    /**
     * Provide a {@link io.jenkins.plugins.checks.util.CapturingChecksPublisher} to capture details.
     */
    @TestExtension
    public static class CapturingChecksPublisherTestExtension extends CapturingChecksPublisher.Factory {
        // activate test extension
    }

    /**
     * Provide inject an implementation of {@link AbstractStatusChecksProperties} to control the checks.
     */
    @TestExtension
    public static class ChecksPropertiesTestExtension extends ChecksProperties {
        // activate test extension
    }

    static class ChecksProperties extends AbstractStatusChecksProperties {
        private boolean applicable;
        private boolean skipped;
        private String name;
        private boolean suppressLogs;

        public void setApplicable(final boolean applicable) {
            this.applicable = applicable;
        }

        public void setSkipped(final boolean skipped) {
            this.skipped = skipped;
        }

        public void setName(final String name) {
            this.name = name;
        }

        public void setSuppressLogs(final boolean suppressLogs) {
            this.suppressLogs = suppressLogs;
        }

        @Override
        public boolean isApplicable(final Job<?, ?> job) {
            return applicable;
        }

        @Override
        public String getName(final Job<?, ?> job) {
            return name;
        }

        @Override
        public boolean isSkipped(final Job<?, ?> job) {
            return skipped;
        }

        @Override
        public boolean isSuppressLogs(final Job<?, ?> job) {
            return suppressLogs;
        }
    }
}
