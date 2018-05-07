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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLStreamException;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.metadata.ArtifactMetadataRetrievalException;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.mojo.versions.AbstractVersionsDependencyUpdaterMojo;
import org.codehaus.mojo.versions.Property;
import org.codehaus.mojo.versions.UpdatePropertiesMojo;
import org.codehaus.mojo.versions.UseLatestReleasesMojo;
import org.codehaus.mojo.versions.api.PomHelper;
import org.codehaus.mojo.versions.api.PropertyVersions;
import org.codehaus.mojo.versions.rewriting.ModifiedPomXMLEventReader;

/**
 * Similar to {@link UseLatestReleasesMojo} plus {@link UpdatePropertiesMojo} but uses {@link UpdateChecker}.
 */
@Mojo(name = "update", requiresDirectInvocation = true)
public class UpdateMojo extends AbstractVersionsDependencyUpdaterMojo {

    /**
     * Whether to only process versions which are already incremental, or all versions.
     * An incremental version would look like {@code 1.23-rc1234.abc123def456}.
     * Even if only incremental versions are being updated,
     * they may be updated <em>to</em> a nonincremental version,
     * if a formal release has been cut which is newer.
     */
    @Parameter(property = "updateNonincremental", defaultValue = "true")
    private boolean updateNonincremental;

    /**
     * Branch to inspect for eligible incremental updates.
     * May be a simple Git branch name, or {@code forker:fork_branch} to search in forked PRs.
     */
    @Parameter(property = "branch", defaultValue = "master")
    private String branch;

    @Override protected void update(ModifiedPomXMLEventReader pom) throws MojoExecutionException, MojoFailureException, XMLStreamException, ArtifactMetadataRetrievalException {
        try {
            if (isProcessingDependencyManagement()) {
                DependencyManagement dependencyManagement = getProject().getDependencyManagement();
                if (dependencyManagement != null) {
                    update(pom, dependencyManagement.getDependencies());
                }
            }
            if (isProcessingDependencies()) {
                List<Dependency> dependencies = getProject().getDependencies();
                if (dependencies != null) {
                    update(pom, dependencies);
                }
            }
            for (Map.Entry<Property, PropertyVersions> entry : getHelper().getVersionPropertiesMap(getProject(), /* TODO */ new Property[0], /* TODO */ null, null, true).entrySet()) {
                Property property = entry.getKey();
                PropertyVersions versions = entry.getValue();
                // TODO as in UpdatePropertiesMojo
            }
        } catch (MojoExecutionException | MojoFailureException | XMLStreamException | ArtifactMetadataRetrievalException x) {
            throw x;
        } catch (Exception x) {
            throw new MojoExecutionException("Update failed", x);
        }
    }

    private void update(ModifiedPomXMLEventReader pom, List<Dependency> dependencies) throws Exception {
        for (Dependency dep : dependencies) {
            if (isExcludeReactor() && isProducedByReactor(dep)) {
                getLog().info("Skipping reactor dep " + toString(dep));
                continue;
            }
            String version = dep.getVersion();
            if (ArtifactUtils.isSnapshot(version)) {
                getLog().info("Skipping snapshot dep " + toString(dep));
                continue;
            }
            if (!updateNonincremental && !version.matches(".+-rc[0-9]+[.][0-9a-f]{12}")) {
                getLog().info("Skipping nonincremental dep " + toString(dep));
                continue;
            }
            Artifact art = toArtifact(dep);
            if (!isIncluded(art)) {
                getLog().debug("Skipping " + toString(dep));
                continue;
            }
            String groupId = art.getGroupId();
            String artifactId = art.getArtifactId();
            // TODO need to add a caching layer here, as it can be called repeatedly with the same arguments in a reactor build
            UpdateChecker.VersionAndRepo result = UpdateChecker.find(groupId, artifactId, version,
                branch,
                // TODO pick this up from the project’s <repositories>
                Arrays.asList("https://repo.jenkins-ci.org/releases/", "https://repo.jenkins-ci.org/incrementals/"),
                message -> getLog().info(message));
            if (result == null) {
                getLog().info("No update found for " + toString(dep));
            } else {
                getLog().info("Can update " + toString(dep) + " to " + result.version);
                PomHelper.setDependencyVersion(pom, groupId, artifactId, version, result.version.toString(), getProject().getModel());
            }
        }
    }

}
