package io.quarkus.bot.reportstatus;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.SerializationFeature;
import org.jboss.logging.Logger;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.quarkiverse.githubaction.Action;
import io.quarkiverse.githubaction.Commands;
import io.quarkiverse.githubaction.Context;
import io.quarkiverse.githubaction.Inputs;

public class ReportStatusInIssue {

    private static final Logger LOG = Logger.getLogger(ReportStatusInIssue.class);

    private static final String STATUS_MARKER = "<!-- status.quarkus.io/status:";
    private static final String END_OF_MARKER = "-->";
    private static final Pattern STATUS_PATTERN = Pattern.compile(STATUS_MARKER + "\r?\n(.*?)\r?\n" + END_OF_MARKER,
            Pattern.DOTALL);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(new YAMLFactory());

    static {
        OBJECT_MAPPER.registerModule(new JavaTimeModule());
        OBJECT_MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Action
    void reportStatus(Commands commands, Inputs inputs, Context context) throws IOException {
        String status = inputs.getRequired(InputKeys.STATUS);
        String issueRepository = inputs.getRequired(InputKeys.ISSUE_REPOSITORY);
        int issueNumber = inputs.getRequiredInt(InputKeys.ISSUE_NUMBER);
        String repositoryName = inputs.get(InputKeys.REPOSITORY).orElse(context.getGitHubRepository());
        Long runId = inputs.getLong(InputKeys.RUN_ID).orElse(context.getGitHubRunId());

        final boolean succeed = "success".equalsIgnoreCase(status);
        if ("cancelled".equalsIgnoreCase(status)) {
            commands.warning("Job status is `cancelled` - exiting");
            return;
        }

        commands.notice(String.format("The CI build had status %s.", status));

        final GitHub github = new GitHubBuilder().withOAuthToken(inputs.getGitHubToken().get()).build();
        final GHRepository repository = github.getRepository(issueRepository);

        final GHIssue issue = repository.getIssue(issueNumber);
        if (issue == null) {
            commands.error(String.format("Unable to find the issue %s in repository %s", issueNumber, issueRepository));
            return;
        } else {
            commands.notice(
                    String.format("Report issue found: %s - %s", issue.getTitle(), issue.getHtmlUrl().toString()));
            commands.notice(String.format("The issue is currently %s", issue.getState().toString()));
        }

        String quarkusSha = inputs.get(InputKeys.QUARKUS_SHA).orElse(null);
        String projectSha = inputs.get(InputKeys.PROJECT_SHA).orElse(null);
        Status existingStatus = extractStatus(issue.getBody());
        State newState = new State(Instant.now(), quarkusSha, projectSha);

        final State firstFailure;
        final State lastFailure;
        final State lastSuccess;

        if (succeed) {
            firstFailure = null;
            lastFailure = null;
            lastSuccess = newState;

            if (isOpen(issue)) {
                // close issue with a comment
                final GHIssueComment comment = issue.comment(
                        String.format("Build fixed:\n* Link to latest CI run: https://github.com/%s/actions/runs/%s",
                                repositoryName, runId));
                issue.close();
                commands.notice(String.format("Comment added on issue %s - %s, the issue has also been closed",
                        issue.getHtmlUrl().toString(), comment.getHtmlUrl().toString()));
            }
        } else {
            lastSuccess = State.KEEP_EXISTING;
            lastFailure = newState;

            if (isOpen(issue)) {
                final GHIssueComment comment = issue.comment(String.format(
                        "The build is still failing:\n* Link to latest CI run: https://github.com/%s/actions/runs/%s",
                        repositoryName, runId));
                commands.notice(String.format("Comment added on issue %s - %s", issue.getHtmlUrl().toString(),
                        comment.getHtmlUrl().toString()));

                // for old reports, we won't have the first failure previously set so let's set it to the new state as an approximation
                firstFailure = existingStatus.firstFailure() != null ? State.KEEP_EXISTING : newState;
            } else {
                issue.reopen();
                final GHIssueComment comment = issue.comment(String.format(
                        "Unfortunately, the build failed:\n* Link to latest CI run: https://github.com/%s/actions/runs/%s",
                        repositoryName, runId));
                commands.notice(String.format("Comment added on issue %s - %s, the issue has been re-opened",
                        issue.getHtmlUrl().toString(), comment.getHtmlUrl().toString()));

                firstFailure = newState;
            }
        }

        Status newStatus;
        if (existingStatus != null) {
            newStatus = new Status(Instant.now(), !succeed, repositoryName, runId, quarkusSha, projectSha,
                    firstFailure == State.KEEP_EXISTING ? existingStatus.firstFailure() : firstFailure,
                    lastFailure == State.KEEP_EXISTING ? existingStatus.lastFailure() : lastFailure,
                    lastSuccess == State.KEEP_EXISTING ? existingStatus.lastSuccess() : lastSuccess);
        } else {
            newStatus = new Status(Instant.now(), !succeed, repositoryName, runId, quarkusSha, projectSha,
                    firstFailure, lastFailure, lastSuccess);
        }

        issue.setBody(appendStatusInformation(issue.getBody(), newStatus));
    }

    private static boolean isOpen(GHIssue issue) {
        return (issue.getState() == GHIssueState.OPEN);
    }

    public String appendStatusInformation(String body, Status status) {
        try {
            String descriptor = STATUS_MARKER + "\n" + OBJECT_MAPPER.writeValueAsString(status) + END_OF_MARKER;

            if (!body.contains(STATUS_MARKER)) {
                return body + "\n\n" + descriptor;
            }

            return STATUS_PATTERN.matcher(body).replaceFirst(descriptor);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to update the status descriptor", e);
        }
    }

    public Status extractStatus(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }

        Matcher matcher = STATUS_PATTERN.matcher(body);
        if (!matcher.find()) {
            return null;
        }

        try {
            return OBJECT_MAPPER.readValue(matcher.group(1), Status.class);
        } catch (Exception e) {
            LOG.warn("Unable to extract Status from issue body", e);
            return null;
        }
    }

    public record Status(Instant updatedAt, boolean failure, String repository, Long runId,
             String quarkusSha, String projectSha, State firstFailure, State lastFailure, State lastSuccess) {
    }

    public record State(Instant date, String quarkusSha, String projectSha) {

        /**
         * Sentinel value to keep the existing value.
         */
        private static final State KEEP_EXISTING = new State(null, null, null);
    }
}