/*
 * The MIT License
 *
 * Copyright 2018 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.jenkins.tools.incrementals.maven;

import io.jenkins.tools.incrementals.lib.UpdateChecker;
import io.jenkins.tools.incrementals.maven.util.PluginRef;
import io.jenkins.tools.incrementals.maven.util.PluginRefList;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.apache.maven.plugins.annotations.LifecyclePhase.PACKAGE;

/**
 * Updates plugins.txt for official Docker images.
 * @author Oleg Nenashev
 * @since TODO
 */
@Mojo(name="updatePluginsTxt", defaultPhase = PACKAGE, requiresProject = false)
public class UpdatePluginsTxt extends AbstractMojo implements DependencyManagementMojo {

    /**
     * Path to the plugins.txt file
     */
    @Parameter(property = "pluginsFile", required = true)
    public String pluginsFile;

    //TODO: Implement
    /**
     * Whether to only process versions which are already incremental, or all versions.
     * An incremental version would look like {@code 1.23-rc1234.abc123def456}.
     * Even if only incremental versions are being updated,
     * they may be updated <em>to</em> a nonincremental version,
     * if a formal release has been cut which is newer.
     */
    //@Parameter(property = "updateNonincremental", defaultValue = "false")
    private boolean updateNonincremental = false;

    /**
     * Branch to inspect for eligible incremental updates.
     * May be a simple Git branch name, or {@code forker:fork_branch} to search in forked PRs.
     */
    @Parameter(property = "branch", defaultValue = "master")
    private String branch;

    //@Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true)
    //private List<MavenArtifactRepository> repos;

    @Override
    public void execute() throws MojoExecutionException {
        UpdateChecker checker = new UpdateChecker(message -> getLog().info(message),
                // TODO use repos.stream().map(MavenArtifactRepository::getUrl).collect(Collectors.toList()) if UpdateChecker.loadVersions is fixed to exclude snapshots and pass authentication
                Arrays.asList("https://repo.jenkins-ci.org/releases/", "https://repo.jenkins-ci.org/incrementals/"));

        File file = new File(pluginsFile);
        if (!file.exists()) {
            throw new MojoExecutionException("File does not exist: " + pluginsFile);
        }
        if(!file.isFile()) {
            throw new MojoExecutionException("Path is not a file: " + pluginsFile);
        }

        final PluginRefList pluginsTxt;
        try {
            pluginsTxt = PluginRefList.fromFile(file);
        } catch (IOException ex) {
            throw new MojoExecutionException("Failed to read the plugins list file", ex);
        }

        // Update the file
        update(pluginsTxt, checker);

        // Write result
        try {
            pluginsTxt.writeToFile(file);
        } catch (IOException ex) {
            throw new MojoExecutionException("Failed to update plugins.txt file " + file, ex);
        }
        getLog().info("Updated plugins.txt: " + file);
    }

    private void update(List<PluginRef> dependencies, UpdateChecker checker) throws MojoExecutionException {
        for (PluginRef dep : dependencies) {
            if (dep.isComment()) {
                continue; // skip comments
            }

            if (!updateNonincremental && !dep.isIncrementals()) {
                getLog().info("Skipping non-incrementals dependency: " + dep);
                continue;
            }

            String version = dep.getVersion();
            if (version == null) {
                // Tagged versions will be skipped as well
                getLog().info("Skipping plugin without version definition: " + dep);
                continue;
            }
            if (ArtifactUtils.isSnapshot(version)) {
                getLog().info("Skipping plugin with snapshot version: " + dep);
                continue;
            }
            if (!updateNonincremental && !version.matches(".+-rc[0-9]+[.][0-9a-f]{12}")) {
                getLog().debug("Skipping plugin with non-incremental version " + dep);
                continue;
            }

            String artifactId = dep.getArtifactId();
            if (artifactId == null) {
                throw new MojoExecutionException("No artifact ID for the dependency: " + dep);
            }

            String groupId = dep.getGroupId();
            if (groupId == null) {
                try {
                    groupId = checker.findGroupId(artifactId);
                } catch (IOException|InterruptedException ex) {
                    throw new MojoExecutionException("Cannot find group ID for plugin: " + artifactId, ex);
                }
            }

            String effectiveBranch = branch;
            if (dep.getGithubBranch() != null) {
                effectiveBranch = dep.getGithubBranch();
                if (dep.getGithubUser() != null) {
                    effectiveBranch = dep.getGithubUser() + ":" + effectiveBranch;
                }
            }

            // TODO need to add a caching layer here, as it can be called repeatedly with the same arguments in a reactor build
            final UpdateChecker.VersionAndRepo result;
            try {
                result = checker.find(groupId, artifactId, version, effectiveBranch);
            } catch (Exception ex) {
                throw new MojoExecutionException("Cannot check for updates", ex);
            }

            if (result == null) {
                getLog().info("No update found for " + dep);
            } else {
                getLog().info("Can update dependency " + dep + " to " + result.version);
                dep.setVersion(result.version.getCanonical());
            }
        }
    }
}
