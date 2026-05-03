package com.poc.unleash.steps;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;

/*
 * These are the REAL imports based on actual source code.
 * CDIMojoProcessingStep is the interface all steps implement.
 * ProcessingStep is the annotation that gives the step its id.
 * Logger is the CDI logging wrapper used throughout the plugin.
 */
import com.itemis.maven.plugins.cdi.CDIMojoProcessingStep;
import com.itemis.maven.plugins.cdi.ExecutionContext;
import com.itemis.maven.plugins.cdi.annotations.ProcessingStep;
import com.itemis.maven.plugins.cdi.logging.Logger;

import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Custom step: Detects Git submodules and validates they
 * have all been released before the parent is released.
 *
 * This fills the gap in unleash's multi-repo support —
 * it has zero native awareness that submodules exist.
 */
@ProcessingStep(
        id = "checkSubmoduleVersions",
        description = "Validates all Git submodules are at release versions",
        requiresOnline = false   // no network needed for this check
)
public class CheckSubmoduleVersions implements CDIMojoProcessingStep {

    /*
     * @Inject + @Named("reactorProjects") = exactly how the
     * real CheckProjectVersions.java injects its project list.
     * The name "reactorProjects" is produced by AbstractUnleashMojo.
     */
    @Inject
    private Logger log;

    @Inject
    @Named("reactorProjects")
    private List<MavenProject> reactorProjects;

    @Override
    public void execute(ExecutionContext context)
            throws MojoExecutionException, MojoFailureException {

        this.log.info("Checking Git submodule versions...");

        // Get the root project's base directory
        MavenProject rootProject = reactorProjects.get(0);
        File rootDir = rootProject.getBasedir();

        // Read .gitmodules to find submodules
        File gitmodules = new File(rootDir, ".gitmodules");

        if (!gitmodules.exists()) {
            this.log.info("No .gitmodules found — not a multi-repo setup. Skipping.");
            return;
        }

        this.log.info(".gitmodules found — scanning submodule versions...");

        try {
            String content = new String(Files.readAllBytes(gitmodules.toPath()));

            // Extract submodule paths from .gitmodules
            // Format: path = poc-util
            String[] lines = content.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("path = ")) {
                    String submodulePath = line.replace("path = ", "").trim();
                    validateSubmodule(rootDir, submodulePath);
                }
            }

        } catch (Exception e) {
            throw new MojoExecutionException(
                    "Failed to read .gitmodules: " + e.getMessage(), e);
        }
    }

    private void validateSubmodule(File rootDir, String path)
            throws MojoFailureException {

        File subPom = new File(rootDir, path + "/pom.xml");

        if (!subPom.exists()) {
            this.log.warn("Submodule '" + path
                    + "' has no pom.xml — skipping version check.");
            return;
        }

        try {
            String pomContent = new String(
                    Files.readAllBytes(subPom.toPath()));

            // Check if version tag contains -SNAPSHOT
            if (pomContent.matches("(?s).*<version>[^<]*-SNAPSHOT[^<]*</version>.*")) {
                String error = "Submodule '" + path
                        + "' has an unreleased SNAPSHOT version. "
                        + "You must release it first: "
                        + "cd " + path + " && mvn unleash:perform";

                this.log.error(error);
                throw new MojoFailureException(error);
            }

            this.log.info("  ✅ Submodule '" + path
                    + "' is at a release version.");

        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoFailureException(
                    "Could not read pom.xml for submodule '"
                            + path + "': " + e.getMessage());
        }
    }
}
