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
 * The standard unleash-maven-plugin only sees one Git repo at a time.
 * In a multi-repo setup (repos linked via Git submodules), a developer
 * would normally have to manually trigger unleash in each repo in the
 * correct dependency order, updating pom.xml files in between.
 *
 * This step automates the entire process: run unleash once from any
 * module, and it automatically:
 *   1. Releases all downstream submodules in order
 *   2. Updates cross-repo dependency versions before each release
 *   3. Bumps downstream poms to the new dev SNAPSHOT after each release
 *
 * ─────────────────────────────────────────────────────────────────
 * EXECUTION FLOW
 * ─────────────────────────────────────────────────────────────────
 * 1. Read .gitmodules to discover all submodules
 * 2. Skip upstream modules (listed before current in .gitmodules)
 * 3. Skip self (current project)
 * 4. For each downstream submodule still on SNAPSHOT:
 *    a. PRE-RELEASE:  replace its SNAPSHOT sibling deps with release versions
 *    b. RELEASE:      trigger mvn unleash:perform via Maven Invoker
 *    c. POST-RELEASE: update all other modules to reference the new dev SNAPSHOT
 *
 * ─────────────────────────────────────────────────────────────────
 * UPSTREAM vs DOWNSTREAM
 * ─────────────────────────────────────────────────────────────────
 * The order of modules in .gitmodules defines the dependency direction:
 *   poc-util (index 0) → poc-service (index 1) → poc-api (index 2)
 *
 * Running from poc-util (index 0): orchestrates poc-service, poc-api
 * Running from poc-service (index 1): orchestrates poc-api only
 * Running from poc-api (index 2): orchestrates nothing
 *
 * This prevents a downstream module from accidentally re-releasing an
 * upstream dependency that is already on its own SNAPSHOT version.
 *
 * ─────────────────────────────────────────────────────────────────
 * INFINITE LOOP PREVENTION
 * ─────────────────────────────────────────────────────────────────
 * When this step triggers poc-service, poc-service also has this step
 * in its workflow. To prevent poc-service from orchestrating again,
 * we set an environment variable UNLEASH_ORCHESTRATED=true on every
 * child Maven process. If that flag is present at the top of execute(),
 * we immediately return — child processes just release themselves.
 *
 * Why env var and not a Maven property (-D)?
 * Maven properties set via Invoker.setProperties() become Maven user
 * properties, NOT JVM system properties. System.getProperty() cannot
 * read them in a forked JVM. Environment variables are reliably
 * inherited by child processes — that is why System.getenv() works.
 *
 * ─────────────────────────────────────────────────────────────────
 * FULLY AUTOMATIC VERSION MANAGEMENT
 * ─────────────────────────────────────────────────────────────────
 * After each submodule releases, unleash bumps it to the next dev
 * SNAPSHOT (e.g. 1.0.6 → 1.0.7-SNAPSHOT). This step reads that new
 * version from the pom and immediately pushes it to all downstream
 * modules — so no manual pom updates are ever needed between cycles.
 */
@ProcessingStep(
        id = "orchestrateSubmodules",
        description = "Releases downstream submodules in order, manages cross-repo versions automatically",
        requiresOnline = true
)
public class OrchestrateSubmoduleRelease implements CDIMojoProcessingStep {

    @Inject
    private Logger log;

    /**
     * Gives us the base directory of the project unleash is currently running on.
     * Used to locate the parent folder and .gitmodules file.
     */
    @Inject
    private MavenProject project;

    /**
     * SCM credentials injected from the user's command line:
     *   -Dunleash.scmUsername=... -Dunleash.scmPassword=...
     * Forwarded to child Maven Invoker calls and JGit push operations.
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
        // Child processes triggered by this step have UNLEASH_ORCHESTRATED=true
        // set on their environment. If we detect it, skip orchestration and
        // just release this module — prevents recursive triggering.
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

        // Canonical path resolves symlinks and relative segments for reliable comparison
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
        // The position of the current module in .gitmodules determines which
        // other modules are upstream (before) vs downstream (after).
        // We only release downstream modules — upstream ones are dependencies
        // that either already released themselves or are not our responsibility.
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
            // Modules at a lower index are upstream dependencies — skip them.
            // Example: poc-service (index 1) must NOT re-release poc-util
            // (index 0) even if poc-util is currently on a SNAPSHOT version.
            if (i < currentIndex) {
                this.log.info("  ⏭  Skipping [" + path
                        + "] — upstream dependency (index " + i
                        + " < current index " + currentIndex + ").");
                skipped.add(path);
                continue;
            }

            // ── SELF-SKIP ────────────────────────────────────────────────
            // Use canonical path comparison — safer than string comparison
            // because .gitmodules may format paths as ./poc-util vs poc-util.
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
            // If the pom.xml has no -SNAPSHOT it is already on a release
            // version — nothing to do for this module.
            if (!needsRelease(submodulePom)) {
                this.log.info("  ⏭  Skipping [" + path
                        + "] — already at release version.");
                skipped.add(path);
                continue;
            }

            // ── PRE-RELEASE: CLEAN SNAPSHOT DEPS ─────────────────────────
            // Before releasing, replace any sibling SNAPSHOT dependencies in
            // this module's pom with their computed release versions.
            // Unleash's checkDependencies step rejects SNAPSHOT deps — so this
            // must happen before triggerRelease().
            this.log.info("  🚀 Releasing submodule: " + path);
            updateSubmoduleDepsBeforeRelease(submoduleDir, submodulePaths, parentDir);

            // ── RELEASE ──────────────────────────────────────────────────
            triggerRelease(submoduleDir);
            released.add(path);
            this.log.info("  ✅ Done: " + path);

            // ── POST-RELEASE: PUSH NEW DEV SNAPSHOT TO DOWNSTREAM ────────
            // After release, unleash bumps this module to its next dev SNAPSHOT
            // (e.g. 1.0.6 → 1.0.7-SNAPSHOT). We immediately read that new
            // version and push it to all downstream poms so no manual updates
            // are ever needed between release cycles.
            updateDependentPoms(path, submoduleDir, submodulePaths, parentDir);
        }

        this.log.info("=== Orchestration Summary ===");
        this.log.info("Released : " + released);
        this.log.info("Skipped  : " + skipped);
    }

    /**
     * Returns true if the given pom.xml contains a -SNAPSHOT version string.
     *
     * Used as a quick filter to skip modules that are already on a release
     * version. Unleash handles the actual version logic — we just check
     * whether this module needs to be released at all.
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
     * Spawns a child Maven process to run "mvn unleash:perform" in the
     * given submodule directory using Maven Invoker.
     *
     * Key configuration decisions:
     *
     * setBatchMode(true)
     *   Equivalent to mvn -B. Without this, unleash's calculateVersions
     *   step prompts interactively for the release version. The child has
     *   no terminal attached so it would hang forever waiting for input.
     *
     * setLocalRepositoryDirectory()
     *   The child Maven is a completely fresh JVM. Without this, it would
     *   try to download unleash-submodule-extension from Maven Central
     *   where it does not exist (it is only in the local ~/.m2 repository).
     *
     * UNLEASH_ORCHESTRATED=true (env var, not Maven property)
     *   Prevents the child from orchestrating again (infinite loop guard).
     *   Maven properties via setProperties() are user properties, not JVM
     *   system properties — System.getProperty() cannot read them in a
     *   forked process. Environment variables are reliably inherited.
     */
    private void triggerRelease(File directory)
            throws MojoFailureException, MojoExecutionException {

        InvocationRequest request = new DefaultInvocationRequest();
        request.setBaseDirectory(directory);
        request.setGoals(Arrays.asList("unleash:perform"));
        request.setBatchMode(true);

        Properties props = new Properties();
        props.setProperty("unleash.scmUsername", this.scmUsername);
        props.setProperty("unleash.scmPassword", this.scmPassword);

        File workflowFile = new File(directory, "submodule-workflow.wf");
        if (workflowFile.exists()) {
            props.setProperty("workflow", workflowFile.getAbsolutePath());
        }
        request.setProperties(props);

        String localRepoPath = System.getProperty("maven.repo.local");
        if (localRepoPath == null) {
            localRepoPath = System.getProperty("user.home") + "/.m2/repository";
        }
        request.setLocalRepositoryDirectory(new File(localRepoPath));

        request.addShellEnvironment("UNLEASH_ORCHESTRATED", "true");
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
     * Reads .gitmodules and extracts the path value for each submodule.
     *
     * .gitmodules format:
     *   [submodule "poc-util"]
     *       path = poc-util          ← extracted
     *       url  = https://github.com/...
     *
     * Returns: ["poc-util", "poc-service", "poc-api"]
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
     * POST-RELEASE: updates all downstream modules to reference the new dev
     * SNAPSHOT version of the just-released module.
     *
     * WHY THIS IS NEEDED:
     *   After poc-service releases 1.0.6, unleash bumps it to 1.0.7-SNAPSHOT.
     *   Any module that depended on poc-service (e.g. poc-api) may still have
     *   an outdated reference — either a stale release version (1.0.5) or
     *   the previous SNAPSHOT (1.0.6-SNAPSHOT).
     *
     *   This method reads the NEW dev version from the released pom (1.0.7-SNAPSHOT)
     *   and replaces whatever version downstream modules currently have — no
     *   manual pom updates are ever needed between release cycles.
     *
     * WHAT IT REPLACES:
     *   Any version (SNAPSHOT or release) → new dev SNAPSHOT
     *   e.g. poc-api has poc-service:1.0.5   → updated to poc-service:1.0.7-SNAPSHOT
     *   e.g. poc-api has poc-service:1.0.6-SNAPSHOT → updated to poc-service:1.0.7-SNAPSHOT
     *
     * After this update, the next release cycle's updateSubmoduleDepsBeforeRelease()
     * will find poc-service:1.0.7-SNAPSHOT in poc-api and correctly resolve it
     * to poc-service:1.0.7 before releasing poc-api.
     */
    private void updateDependentPoms(String releasedPath, File releasedDir,
                                     List<String> allPaths, File parentDir)
            throws MojoExecutionException, MojoFailureException {

        File releasedPom = new File(releasedDir, "pom.xml");
        if (!releasedPom.exists()) return;

        String[] ga;
        try {
            ga = extractGroupArtifact(releasedPom);
        } catch (Exception e) {
            throw new MojoExecutionException(
                    "Cannot read released pom: " + releasedPom, e);
        }
        if (ga == null) return;

        String releasedArtifactId = ga[1];

        // Read the new dev SNAPSHOT version unleash just committed into the pom.
        // e.g. poc-service just released 1.0.6 → pom now shows 1.0.7-SNAPSHOT
        String newSnapshotVersion = extractCurrentVersion(releasedPom);
        if (newSnapshotVersion == null) {
            this.log.warn("  [POST-RELEASE] Could not read new version from "
                    + releasedPom + " — skipping downstream update");
            return;
        }
        this.log.info("  [POST-RELEASE] " + releasedArtifactId
                + " bumped to: " + newSnapshotVersion
                + " — updating downstream modules");

        for (String otherPath : allPaths) {
            if (otherPath.equals(releasedPath)) continue;

            File otherDir = new File(parentDir, otherPath);
            File otherPom = new File(otherDir, "pom.xml");
            if (!otherPom.exists()) continue;

            try {
                String content = new String(Files.readAllBytes(otherPom.toPath()));

                // Replace whatever version the downstream module currently has
                // for this dependency — could be a stale release or old SNAPSHOT
                String updated = replaceAnyVersion(content, releasedArtifactId,
                        newSnapshotVersion);

                if (!updated.equals(content)) {
                    Files.write(otherPom.toPath(), updated.getBytes());
                    this.log.info("  📝 Updated " + otherPath + "/pom.xml"
                            + " — " + releasedArtifactId
                            + " → " + newSnapshotVersion);
                    commitAndPush(otherDir,
                            "[unleash-release] update " + releasedArtifactId
                                    + " to " + newSnapshotVersion
                                    + " for next dev cycle");
                }
            } catch (IOException e) {
                throw new MojoExecutionException(
                        "Cannot update pom.xml in " + otherPath, e);
            }
        }
    }

    /**
     * PRE-RELEASE: updates SNAPSHOT sibling dependencies in a module's pom
     * to their computed release versions, before that module is released.
     *
     * WHY THIS IS NEEDED:
     *   poc-service depends on poc-util:1.0.6-SNAPSHOT.
     *   Unleash's checkDependencies step REJECTS any release containing SNAPSHOT
     *   deps. So the version must be resolved to a release version first.
     *
     * HOW WE KNOW THE RELEASE VERSION:
     *   We read the sibling's current pom. If poc-util shows 1.0.6-SNAPSHOT,
     *   its release version will be 1.0.6 (unleash always strips -SNAPSHOT).
     *   We make this replacement before triggering the release.
     *
     * NOTE: Only SNAPSHOT versions are replaced here. Release versions (e.g.
     *   1.0.5) are left alone — they are already valid for checkDependencies.
     *   The POST-RELEASE step (updateDependentPoms) ensures that after each
     *   release, all downstream poms are updated to the new SNAPSHOT, so the
     *   next cycle's PRE-RELEASE step always finds a SNAPSHOT to work with.
     *
     * Example:
     *   poc-util is currently 1.0.6-SNAPSHOT → will release as 1.0.6
     *   poc-service/pom.xml: poc-util:1.0.6-SNAPSHOT → poc-util:1.0.6
     *   → committed and pushed → THEN poc-service is released cleanly ✅
     */
    private void updateSubmoduleDepsBeforeRelease(File submoduleDir,
                                                  List<String> allPaths,
                                                  File parentDir)
            throws MojoExecutionException, MojoFailureException {

        File pomToUpdate = new File(submoduleDir, "pom.xml");
        if (!pomToUpdate.exists()) return;

        this.log.info("  [PRE-RELEASE] Checking " + submoduleDir.getName()
                + "/pom.xml for SNAPSHOT deps on sibling submodules...");

        boolean anyUpdated = false;

        for (String siblingPath : allPaths) {
            File siblingDir = new File(parentDir, siblingPath);

            // Skip self
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

            String siblingArtifactId;
            String siblingReleaseVersion;
            try {
                String[] ga = extractGroupArtifact(siblingPom);
                if (ga == null) continue;
                siblingArtifactId = ga[1];

                String siblingPomContent = new String(
                        Files.readAllBytes(siblingPom.toPath()));

                // Only act if the sibling is on a SNAPSHOT — release versions
                // don't need updating since checkDependencies accepts them
                java.util.regex.Matcher versionMatcher =
                        java.util.regex.Pattern
                                .compile("<version>([0-9]+\\.[0-9]+\\.[0-9]+)-SNAPSHOT</version>")
                                .matcher(siblingPomContent);

                if (!versionMatcher.find()) {
                    continue; // sibling is already on a release version
                }
                siblingReleaseVersion = versionMatcher.group(1);

            } catch (IOException e) {
                throw new MojoExecutionException(
                        "Cannot read sibling pom: " + siblingPom, e);
            }

            this.log.info("  [PRE-RELEASE] Sibling [" + siblingArtifactId
                    + "] will release as: " + siblingReleaseVersion);

            try {
                String content = new String(Files.readAllBytes(pomToUpdate.toPath()));
                // Replace X.Y.Z-SNAPSHOT → X.Y.Z for this sibling's artifactId
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

    /**
     * Replaces a SNAPSHOT dependency version with its release version.
     *
     * Splits the pom content on the artifactId tag, then looks ahead 200 chars
     * (staying inside the same dependency block) for a -SNAPSHOT version and
     * strips the suffix.
     *
     * Used by PRE-RELEASE (updateSubmoduleDepsBeforeRelease) only.
     *
     * Example:
     *   BEFORE:  <artifactId>poc-util</artifactId>
     *            <version>1.0.6-SNAPSHOT</version>
     *   AFTER:   <artifactId>poc-util</artifactId>
     *            <version>1.0.6</version>
     *
     * Why string replacement and not DOM parsing?
     * DOM re-serialisation destroys XML formatting, indentation and comments.
     * String replacement preserves the original file structure exactly.
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
            String lookAhead = following.length() > 200
                    ? following.substring(0, 200) : following;

            if (lookAhead.contains("-SNAPSHOT</version>")) {
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
     * Replaces a dependency version with a new version — regardless of what
     * the current version is (SNAPSHOT or release).
     *
     * Used by POST-RELEASE (updateDependentPoms) to push the new dev SNAPSHOT
     * to downstream modules after a release completes.
     *
     * Unlike replaceSnapshotVersion() which only targets -SNAPSHOT versions,
     * this replaces ANY version string found after the artifactId tag.
     *
     * Example:
     *   artifactId = poc-service, newVersion = 1.0.7-SNAPSHOT
     *
     *   BEFORE:  <artifactId>poc-service</artifactId>
     *            <version>1.0.5</version>              ← stale release version
     *   AFTER:   <artifactId>poc-service</artifactId>
     *            <version>1.0.7-SNAPSHOT</version>     ← new dev version
     *
     *   OR:
     *   BEFORE:  <version>1.0.6-SNAPSHOT</version>     ← old SNAPSHOT
     *   AFTER:   <version>1.0.7-SNAPSHOT</version>     ← new dev version
     */
    private String replaceAnyVersion(String pomContent, String artifactId,
                                     String newVersion) {
        String[] parts = pomContent.split(
                "<artifactId>" + artifactId + "</artifactId>");

        if (parts.length < 2) return pomContent;

        StringBuilder result = new StringBuilder();
        result.append(parts[0]);

        for (int i = 1; i < parts.length; i++) {
            result.append("<artifactId>").append(artifactId).append("</artifactId>");

            String following = parts[i];
            String lookAhead = following.length() > 200
                    ? following.substring(0, 200) : following;

            if (lookAhead.contains("<version>")) {
                // Replace whatever version is there with the new SNAPSHOT
                result.append(following.replaceFirst(
                        "<version>[^<]+</version>",
                        "<version>" + newVersion + "</version>"));
            } else {
                result.append(following);
            }
        }
        return result.toString();
    }

    /**
     * Reads the current <version> of a project from its pom.xml using XPath.
     *
     * Called AFTER a submodule releases. At that point, unleash has already
     * committed the bumped dev version into the pom (e.g. 1.0.7-SNAPSHOT).
     * We read it here so we can automatically propagate it to downstream modules.
     *
     * Returns null if the version cannot be read — caller handles the warning.
     */
    private String extractCurrentVersion(File pom) throws MojoExecutionException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            Document doc = builder.parse(pom);
            XPath xpath = XPathFactory.newInstance().newXPath();
            String version = (String) xpath.evaluate(
                    "/project/version", doc, XPathConstants.STRING);
            if (version != null && !version.trim().isEmpty()) {
                return version.trim();
            }
        } catch (Exception e) {
            throw new MojoExecutionException(
                    "Cannot read version from pom: " + pom, e);
        }
        return null;
    }

    /**
     * Commits and pushes all modified files in the given repo using JGit.
     *
     * Used after every pom.xml update — both PRE-RELEASE and POST-RELEASE —
     * so that unleash's own storeScmRevision and checkForScmChanges steps
     * always see a clean, consistent repository state.
     *
     * JGit is available at runtime as a transitive dependency of
     * unleash-scm-provider-git — no extra pom dependency needed.
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
     * Falls back to parent/groupId if the project does not declare its own
     * (standard Maven inheritance pattern).
     *
     * Returns null if neither can be found.
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
}