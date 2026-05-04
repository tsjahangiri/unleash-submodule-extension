package com.poc.unleash.steps;

import com.itemis.maven.plugins.cdi.CDIMojoProcessingStep;
import com.itemis.maven.plugins.cdi.ExecutionContext;
import com.itemis.maven.plugins.cdi.annotations.ProcessingStep;
import com.itemis.maven.plugins.cdi.logging.Logger;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.invoker.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * OrchestrateSubmoduleRelease — CDI Processing Step
 *
 * ─────────────────────────────────────────────────────────────────
 * WHAT PROBLEM DOES THIS SOLVE?
 * ─────────────────────────────────────────────────────────────────
 * Normally, unleash only sees one Git repo at a time. In a multi-repo
 * setup (where repos are linked via Git submodules), you'd have to
 * manually trigger unleash in each repo in the correct order.
 *
 * This step automates that: run unleash once from poc-util, and it
 * automatically releases all dependent submodules first.
 *
 * ─────────────────────────────────────────────────────────────────
 * HOW IT WORKS (high level)
 * ─────────────────────────────────────────────────────────────────
 * 1. Read .gitmodules from the parent directory to discover all submodules
 * 2. Skip the current project (we are already releasing it)
 * 3. For each other submodule that still has a SNAPSHOT version:
 *    a. Trigger mvn unleash:perform on it via Maven Invoker
 *    b. After it succeeds, update any pom.xml files that referenced
 *       it as a SNAPSHOT — replace with the new release version
 *    c. Commit and push those pom updates so the next release sees clean state
 *
 * ─────────────────────────────────────────────────────────────────
 * INFINITE LOOP PREVENTION
 * ─────────────────────────────────────────────────────────────────
 * When this step triggers poc-service, poc-service also has this step
 * in its workflow. To prevent poc-service from triggering everything
 * again, we pass an environment variable UNLEASH_ORCHESTRATED=true
 * to the child Maven process. At the top of execute(), if that flag
 * is present, we immediately return — so child processes just release
 * themselves without orchestrating further.
 *
 * Why env var and not a Maven property (-D)?
 * Maven properties set via Invoker.setProperties() become Maven user
 * properties, NOT JVM system properties. System.getProperty() cannot
 * read them. Environment variables are reliably inherited by the child
 * JVM process — that's why System.getenv() works correctly here.
 */
@ProcessingStep(
        id = "orchestrateSubmodules",
        description = "Triggers unleash:perform on each submodule, then updates cross-repo pom dependencies",
        requiresOnline = true
)
public class OrchestrateSubmoduleRelease implements CDIMojoProcessingStep {

    @Inject
    private Logger log;

    /**
     * Gives us the base directory of the project unleash is currently running on.
     * Used to find the parent folder and locate .gitmodules.
     */
    @Inject
    private MavenProject project;

    /**
     * SCM credentials injected by unleash from the user's command line
     * (-Dunleash.scmUsername / -Dunleash.scmPassword).
     * Passed through to child Maven Invoker calls and JGit push operations.
     */
    @Inject
    @Named("scmUsername")
    private String scmUsername;

    @Inject
    @Named("scmPassword")
    private String scmPassword;

    @Override
    public void execute(ExecutionContext context)
            throws MojoExecutionException, MojoFailureException {

        // ── INFINITE LOOP GUARD ──────────────────────────────────────────
        // If this process was triggered BY an orchestrator (not by the user
        // directly), skip orchestration entirely — just release this module.
        // The env var is set inside triggerRelease() when invoking child Maven.
        String orchestrated = System.getenv("UNLEASH_ORCHESTRATED");
        if ("true".equals(orchestrated)) {
            this.log.info("=== Skipping orchestration — already triggered by orchestrator ===");
            return;
        }

        this.log.info("=== Orchestrating submodule releases ===");

        File currentDir = this.project.getBasedir();
        File parentDir  = currentDir.getParentFile();
        File gitmodules = new File(parentDir, ".gitmodules");

        if (!gitmodules.exists()) {
            this.log.info("No .gitmodules found. Skipping orchestration.");
            return;
        }

        // Use canonical path (fully resolved, no symlinks) for reliable self-detection
        String currentAbsPath;
        try {
            currentAbsPath = currentDir.getCanonicalPath();
        } catch (IOException e) {
            currentAbsPath = currentDir.getAbsolutePath();
        }
        this.log.info("Current project path: " + currentAbsPath);

        List<String> submodulePaths = readSubmodulePaths(gitmodules);
        this.log.info("Found " + submodulePaths.size()
                + " submodule(s): " + submodulePaths);

        List<String> released = new ArrayList<>();
        List<String> skipped  = new ArrayList<>();

        // ── FIND CURRENT MODULE INDEX ────────────────────────────────────
        // We only orchestrate modules listed AFTER us in .gitmodules.
        // Modules listed before us are upstream dependencies — they must
        // not be re-released by a downstream module's orchestration run.
        int currentIndex = -1;
        for (int i = 0; i < submodulePaths.size(); i++) {
            File candidate = new File(parentDir, submodulePaths.get(i).trim());
            String candidateAbs;
            try {
                candidateAbs = candidate.getCanonicalPath();
            } catch (IOException e) {
                candidateAbs = candidate.getAbsolutePath();
            }
            if (candidateAbs.equals(currentAbsPath)) {
                currentIndex = i;
                break;
            }
        }
        this.log.info("Current module index in .gitmodules: " + currentIndex
                + " — will only orchestrate modules at index > " + currentIndex);

        for (int i = 0; i < submodulePaths.size(); i++) {
            String path = submodulePaths.get(i);
            File submoduleDir = new File(parentDir, path.trim());

            String submoduleAbsPath;
            try {
                submoduleAbsPath = submoduleDir.getCanonicalPath();
            } catch (IOException e) {
                submoduleAbsPath = submoduleDir.getAbsolutePath();
            }

            // ── UPSTREAM SKIP ────────────────────────────────────────────
            // Modules listed before the current module are upstream dependencies.
            // They should already be released — do not re-release them.
            // Example: running from poc-service (index 1) must not re-release
            // poc-util (index 0) even if poc-util is still on a SNAPSHOT version.
            if (i < currentIndex) {
                this.log.info("  ⏭  Skipping [" + path
                        + "] — upstream dependency (index " + i
                        + " < current index " + currentIndex + ").");
                skipped.add(path);
                continue;
            }

            // ── SELF-SKIP ────────────────────────────────────────────────
            if (submoduleAbsPath.equals(currentAbsPath)) {
                this.log.info("  ⏭  Skipping [" + path
                        + "] — this is the current project (self).");
                skipped.add(path);
                continue;
            }

            File submodulePom = new File(submoduleDir, "pom.xml");
            if (!submoduleDir.exists() || !submodulePom.exists()) {
                this.log.warn("  ⚠  Submodule dir not found: "
                        + submoduleAbsPath + " — skipping.");
                continue;
            }

            // ── ALREADY RELEASED? ────────────────────────────────────────
            if (!needsRelease(submodulePom)) {
                this.log.info("  ⏭  Skipping [" + path
                        + "] — already at release version.");
                skipped.add(path);
                continue;
            }

            // ── RELEASE ──────────────────────────────────────────────────
            this.log.info("  🚀 Releasing submodule: " + path);
            updateSubmoduleDepsBeforeRelease(submoduleDir, submodulePaths, parentDir);
            triggerRelease(submoduleDir);
            released.add(path);
            this.log.info("  ✅ Done: " + path);

            updateDependentPoms(path, submoduleDir, submodulePaths, parentDir);
        }

        this.log.info("=== Orchestration Summary ===");
        this.log.info("Released : " + released);
        this.log.info("Skipped  : " + skipped);
    }

    /**
     * Returns true if the given pom.xml contains a -SNAPSHOT version string.
     *
     * This is our signal that the submodule still needs to be released.
     * unleash itself does the proper version bumping — we just use this
     * as a quick filter to skip already-released modules.
     */
    private boolean needsRelease(File pom) throws MojoExecutionException {
        try {
            String content = new String(Files.readAllBytes(pom.toPath()));
            return content.contains("-SNAPSHOT");
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Cannot read pom.xml: " + pom.getPath(), e);
        }
    }

    /**
     * Spawns a new Maven process to run "mvn unleash:perform" inside the
     * given submodule directory, using Maven Invoker.
     *
     * Key configuration:
     * - setBatchMode(true)            → disables interactive version prompts (-B flag)
     * - setLocalRepositoryDirectory() → child Maven uses same local .m2 cache,
     *                                   so it finds unleash-submodule-extension
     *                                   which isn't published to Maven Central
     * - UNLEASH_ORCHESTRATED=true     → env var tells child to skip orchestration
     *                                   (prevents infinite loop)
     */
    private void triggerRelease(File directory)
            throws MojoFailureException, MojoExecutionException {

        InvocationRequest request = new DefaultInvocationRequest();
        request.setBaseDirectory(directory);
        request.setGoals(Arrays.asList("unleash:perform"));

        // Batch mode: without this, calculateVersions hangs waiting for
        // keyboard input (release version prompt) since there's no terminal
        request.setBatchMode(true);

        Properties props = new Properties();
        props.setProperty("unleash.scmUsername", this.scmUsername);
        props.setProperty("unleash.scmPassword", this.scmPassword);

        // Pass workflow file if it exists in the submodule directory
        File workflowFile = new File(directory, "submodule-workflow.wf");
        if (workflowFile.exists()) {
            props.setProperty("workflow", workflowFile.getAbsolutePath());
        }
        request.setProperties(props);

        // The child Maven process is a completely fresh JVM — it doesn't
        // inherit the parent's resolved classpath. Without this, it would
        // try to download unleash-submodule-extension from Maven Central
        // and fail (it only exists in your local ~/.m2 repository)
        String localRepoPath = System.getProperty("maven.repo.local");
        if (localRepoPath == null) {
            localRepoPath = System.getProperty("user.home") + "/.m2/repository";
        }
        request.setLocalRepositoryDirectory(new File(localRepoPath));

        // Environment variable (not Maven property) — the only reliable way
        // to pass a flag to a forked JVM. System.getenv() reads this;
        // System.getProperty() would NOT work here.
        request.addShellEnvironment("UNLEASH_ORCHESTRATED", "true");

        // Stream child Maven output into our own log with [SUB] prefix
        request.setOutputHandler(line -> this.log.info("  [SUB] " + line));

        Invoker invoker = new DefaultInvoker();
        try {
            InvocationResult result = invoker.execute(request);
            if (result.getExitCode() != 0) {
                throw new MojoFailureException(
                        "Release FAILED for submodule: " + directory.getName()
                                + " (exit code: " + result.getExitCode() + ")");
            }
        } catch (MavenInvocationException e) {
            throw new MojoExecutionException(
                    "Could not invoke Maven for submodule: "
                            + directory.getName(), e);
        }
    }

    /**
     * Reads .gitmodules and extracts the submodule path values.
     *
     * .gitmodules format:
     *   [submodule "poc-util"]
     *       path = poc-util          ← we extract this
     *       url  = https://github.com/...
     *
     * Returns: ["poc-util", "poc-service"]
     */
    private List<String> readSubmodulePaths(File gitmodules)
            throws MojoExecutionException {
        List<String> paths = new ArrayList<>();
        try {
            String content = new String(Files.readAllBytes(gitmodules.toPath()));
            for (String line : content.split("\n")) {
                line = line.trim();
                if (line.startsWith("path = ")) {
                    paths.add(line.replace("path = ", "").trim());
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot read .gitmodules", e);
        }
        return paths;
    }

    /**
     * Challenge 3 — Cross-repo dependency version update.
     *
     * After a submodule releases, other submodules may still reference it
     * as a SNAPSHOT in their pom.xml. This method:
     *   1. Identifies the just-released submodule's artifactId
     *   2. Scans every other submodule's pom.xml for a SNAPSHOT reference to it
     *   3. Replaces X.Y.Z-SNAPSHOT → X.Y.Z
     *   4. Commits and pushes the change so it's visible to subsequent release steps
     *
     * NOTE: This only updates SNAPSHOT references. If a pom already pins
     * a release version (e.g. 1.0.1), it is left untouched — you must
     * manually update it to the new SNAPSHOT before the next release cycle.
     *
     * Example:
     *   poc-util just released 1.0.2
     *   poc-service/pom.xml has: <version>1.0.2-SNAPSHOT</version>
     *   → after this method:     <version>1.0.2</version>  + commit + push
     */
    private void updateDependentPoms(String releasedPath, File releasedDir,
                                     List<String> allPaths, File parentDir)
            throws MojoExecutionException, MojoFailureException {

        File releasedPom = new File(releasedDir, "pom.xml");
        if (!releasedPom.exists()) return;

        // Read the released module's artifactId so we know what to search for
        String[] ga;
        try {
            ga = extractGroupArtifact(releasedPom);
        } catch (Exception e) {
            throw new MojoExecutionException(
                    "Cannot read released pom: " + releasedPom, e);
        }
        if (ga == null) return;

        String releasedArtifactId = ga[1];

        // Scan every other submodule's pom.xml for a SNAPSHOT reference
        for (String otherPath : allPaths) {
            if (otherPath.equals(releasedPath)) continue;

            File otherDir = new File(parentDir, otherPath);
            File otherPom = new File(otherDir, "pom.xml");
            if (!otherPom.exists()) continue;

            try {
                String content = new String(Files.readAllBytes(otherPom.toPath()));
                String updated = replaceSnapshotVersion(content, releasedArtifactId);

                if (!updated.equals(content)) {
                    Files.write(otherPom.toPath(), updated.getBytes());
                    this.log.info("  📝 Updated " + otherPath + "/pom.xml"
                            + " — " + releasedArtifactId
                            + " SNAPSHOT → release version");

                    commitAndPush(otherDir,
                            "[unleash-release] update " + releasedArtifactId
                                    + " dependency to release version");
                }
            } catch (IOException e) {
                throw new MojoExecutionException(
                        "Cannot update pom.xml in " + otherPath, e);
            }
        }
    }

    /**
     * String-based replacement of a SNAPSHOT dependency version in a pom.xml.
     *
     * Splits on the given artifactId tag, then looks in the next ~200 characters
     * (staying inside the same dependency block) for a version ending in -SNAPSHOT.
     * Strips the -SNAPSHOT suffix to produce the release version.
     *
     * Example transform:
     *   BEFORE:  <artifactId>poc-util</artifactId>
     *            <version>1.0.2-SNAPSHOT</version>
     *
     *   AFTER:   <artifactId>poc-util</artifactId>
     *            <version>1.0.2</version>
     *
     * Why string replacement and not DOM parsing?
     * DOM parsing and re-serialization destroys XML formatting and comments.
     * String replacement is simpler and preserves the original file structure.
     */
    private String replaceSnapshotVersion(String pomContent, String artifactId) {
        String[] parts = pomContent.split(
                "<artifactId>" + artifactId + "</artifactId>");

        if (parts.length < 2) return pomContent;

        StringBuilder result = new StringBuilder();
        result.append(parts[0]);

        for (int i = 1; i < parts.length; i++) {
            result.append("<artifactId>").append(artifactId).append("</artifactId>");

            String following = parts[i];
            // Only look ahead 200 chars — avoids replacing versions in
            // unrelated dependency blocks that happen to have the same artifactId
            String lookAhead = following.length() > 200
                    ? following.substring(0, 200) : following;

            if (lookAhead.contains("-SNAPSHOT</version>")) {
                // Strip -SNAPSHOT from the version tag: 1.0.2-SNAPSHOT → 1.0.2
                result.append(following.replaceFirst(
                        "([0-9]+\\.[0-9]+\\.[0-9]+)-SNAPSHOT</version>",
                        "$1</version>"));
            } else {
                result.append(following);
            }
        }

        return result.toString();
    }

    /**
     * Stages all modified files in the given repo, commits with the given message,
     * and pushes to the remote using the injected SCM credentials.
     *
     * Used after pom.xml cross-dependency updates so the changes are visible
     * to unleash's own storeScmRevision and checkForScmChanges steps.
     *
     * Uses JGit (already on the classpath via unleash-scm-provider-git)
     * — no extra dependency needed.
     */
    private void commitAndPush(File repoDir, String message)
            throws MojoExecutionException {
        try (Git git = Git.open(repoDir)) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage(message)
                    .setAuthor("unleash-bot", "unleash@release.bot")
                    .call();
            git.push()
                    .setCredentialsProvider(
                            new UsernamePasswordCredentialsProvider(
                                    this.scmUsername, this.scmPassword))
                    .call();
            this.log.info("  📤 Committed and pushed pom.xml update in "
                    + repoDir.getName());
        } catch (Exception e) {
            throw new MojoExecutionException(
                    "Failed to commit pom update in " + repoDir.getName(), e);
        }
    }

    /**
     * Parses a pom.xml and returns [groupId, artifactId] of the project.
     * Falls back to parent groupId if the project doesn't declare its own
     * (common in multi-module Maven projects).
     */
    private String[] extractGroupArtifact(File pom) throws MojoExecutionException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            Document doc = builder.parse(pom);
            XPath xpath = XPathFactory.newInstance().newXPath();

            String groupId = (String) xpath.evaluate(
                    "/project/groupId", doc, XPathConstants.STRING);
            String artifactId = (String) xpath.evaluate(
                    "/project/artifactId", doc, XPathConstants.STRING);

            if (groupId == null || groupId.trim().isEmpty()) {
                groupId = (String) xpath.evaluate(
                        "/project/parent/groupId", doc, XPathConstants.STRING);
            }
            if (groupId != null && artifactId != null
                    && !groupId.isEmpty() && !artifactId.isEmpty()) {
                return new String[]{groupId.trim(), artifactId.trim()};
            }
        } catch (Exception e) {
            throw new MojoExecutionException(
                    "Cannot parse pom.xml: " + pom, e);
        }
        return null;
    }

    /**
     * Before releasing a submodule, updates any SNAPSHOT dependencies it has
     * on OTHER sibling submodules, replacing them with the computed release version.
     *
     * WHY THIS IS NEEDED:
     *   poc-service depends on poc-util:1.0.4-SNAPSHOT
     *   unleash's checkDependencies step REJECTS any release that contains SNAPSHOT deps
     *   So we must update poc-service's pom BEFORE triggering its release
     *
     * HOW WE KNOW THE RELEASE VERSION:
     *   We don't need poc-util to be released yet — we just read its current pom.
     *   If poc-util is currently 1.0.4-SNAPSHOT, its release version will be 1.0.4.
     *   This is exactly what unleash itself will do when it releases poc-util.
     *
     * Example:
     *   poc-util current version: 1.0.4-SNAPSHOT  → release version = 1.0.4
     *   poc-service/pom.xml has:  poc-util:1.0.4-SNAPSHOT
     *   → updated to:             poc-util:1.0.4
     *   → committed and pushed
     *   → THEN poc-service is released cleanly
     */
    private void updateSubmoduleDepsBeforeRelease(File submoduleDir,
                                                  List<String> allPaths, File parentDir)
            throws MojoExecutionException, MojoFailureException {

        File pomToUpdate = new File(submoduleDir, "pom.xml");
        if (!pomToUpdate.exists()) return;

        this.log.info("  [PRE-RELEASE] Checking " + submoduleDir.getName()
                + "/pom.xml for SNAPSHOT deps on sibling submodules...");

        boolean anyUpdated = false;

        // For each sibling submodule, check if it is referenced as SNAPSHOT
        // in the submodule we are about to release
        for (String siblingPath : allPaths) {
            File siblingDir = new File(parentDir, siblingPath);

            // Skip self — don't update a module's dependency on itself
            String submoduleAbs;
            String siblingAbs;
            try {
                submoduleAbs = submoduleDir.getCanonicalPath();
                siblingAbs   = siblingDir.getCanonicalPath();
            } catch (IOException e) {
                submoduleAbs = submoduleDir.getAbsolutePath();
                siblingAbs   = siblingDir.getAbsolutePath();
            }
            if (submoduleAbs.equals(siblingAbs)) continue;

            File siblingPom = new File(siblingDir, "pom.xml");
            if (!siblingPom.exists()) continue;

            // Read the sibling's current version (e.g. 1.0.4-SNAPSHOT)
            // and compute what its release version will be (e.g. 1.0.4)
            String siblingArtifactId;
            String siblingReleaseVersion;
            try {
                String[] ga = extractGroupArtifact(siblingPom);
                if (ga == null) continue;
                siblingArtifactId = ga[1];

                String siblingPomContent = new String(
                        Files.readAllBytes(siblingPom.toPath()));

                // Extract current version from sibling pom
                // e.g. <version>1.0.4-SNAPSHOT</version>
                java.util.regex.Matcher versionMatcher =
                        java.util.regex.Pattern
                                .compile("<version>([0-9]+\\.[0-9]+\\.[0-9]+)-SNAPSHOT</version>")
                                .matcher(siblingPomContent);

                if (!versionMatcher.find()) {
                    // Sibling is already on a release version — nothing to update
                    continue;
                }
                // Strip -SNAPSHOT to get the release version
                siblingReleaseVersion = versionMatcher.group(1);

            } catch (IOException e) {
                throw new MojoExecutionException(
                        "Cannot read sibling pom: " + siblingPom, e);
            }

            this.log.info("  [PRE-RELEASE] Sibling [" + siblingArtifactId
                    + "] will release as: " + siblingReleaseVersion);

            // Now update the pom of the submodule we're about to release
            try {
                String content = new String(Files.readAllBytes(pomToUpdate.toPath()));
                String updated = replaceSnapshotVersion(content, siblingArtifactId);

                if (!updated.equals(content)) {
                    Files.write(pomToUpdate.toPath(), updated.getBytes());
                    this.log.info("  [PRE-RELEASE] ✅ Updated "
                            + submoduleDir.getName() + "/pom.xml: "
                            + siblingArtifactId + " SNAPSHOT → "
                            + siblingReleaseVersion);

                    commitAndPush(submoduleDir,
                            "[unleash-release] update " + siblingArtifactId
                                    + " dependency to " + siblingReleaseVersion
                                    + " before release");

                    anyUpdated = true;
                }
            } catch (IOException e) {
                throw new MojoExecutionException(
                        "Cannot update pom.xml in " + submoduleDir.getName(), e);
            }
        }

        if (!anyUpdated) {
            this.log.info("  [PRE-RELEASE] No SNAPSHOT sibling deps found — pom is clean ✅");
        }
    }
}
