package io.quarkus.bot.reportstatus;

import java.io.IOException;
import java.time.Instant;
import java.util.regex.Pattern;

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

    private static final String STATUS_MARKER = "<!-- status.quarkus.io/status:";
    private static final String END_OF_MARKER = "-->";
    private static final Pattern STATUS_PATTERN = Pattern.compile(STATUS_MARKER + "(.*?)" + END_OF_MARKER,
            Pattern.DOTALL);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(new YAMLFactory());

    static {
        OBJECT_MAPPER.registerModule(new JavaTimeModule());
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

        if (succeed) {
            if (issue != null && isOpen(issue)) {
                // close issue with a comment
                final GHIssueComment comment = issue.comment(
                        String.format("Build fixed:\n* Link to latest CI run: https://github.com/%s/actions/runs/%s",
                                repositoryName, runId));
                issue.close();
                commands.notice(String.format("Comment added on issue %s - %s, the issue has also been closed",
                        issue.getHtmlUrl().toString(), comment.getHtmlUrl().toString()));
            } else {
                System.out.println("Nothing to do - the build passed and the issue is already closed");
            }
        } else {
            if (isOpen(issue)) {
                final GHIssueComment comment = issue.comment(String.format(
                        "The build is still failing:\n* Link to latest CI run: https://github.com/%s/actions/runs/%s",
                        repositoryName, runId));
                commands.notice(String.format("Comment added on issue %s - %s", issue.getHtmlUrl().toString(),
                        comment.getHtmlUrl().toString()));
            } else {
                issue.reopen();
                final GHIssueComment comment = issue.comment(String.format(
                        "Unfortunately, the build failed:\n* Link to latest CI run: https://github.com/%s/actions/runs/%s",
                        repositoryName, runId));
                commands.notice(String.format("Comment added on issue %s - %s, the issue has been re-opened",
                        issue.getHtmlUrl().toString(), comment.getHtmlUrl().toString()));
            }
        }

        issue.setBody(appendStatusInformation(issue.getBody(), new Status(Instant.now(), !succeed, repositoryName, runId)));
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

    public record Status(Instant updatedAt, boolean failure, String repository, Long runId) {
    };
}