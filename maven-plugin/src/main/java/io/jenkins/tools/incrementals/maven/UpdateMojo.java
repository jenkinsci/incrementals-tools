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
import javax.inject.Inject;
import javax.xml.stream.XMLStreamException;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.wagon.Wagon;
import org.codehaus.mojo.versions.AbstractVersionsDependencyUpdaterMojo;
import org.codehaus.mojo.versions.UpdatePropertiesMojo;
import org.codehaus.mojo.versions.UseLatestReleasesMojo;
import org.codehaus.mojo.versions.api.ArtifactAssociation;
import org.codehaus.mojo.versions.api.PomHelper;
import org.codehaus.mojo.versions.api.Property;
import org.codehaus.mojo.versions.api.PropertyVersions;
import org.codehaus.mojo.versions.api.VersionsHelper;
import org.codehaus.mojo.versions.api.recording.ChangeRecorder;
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

    @Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true)
    private List<MavenArtifactRepository> repos;

    @Inject public UpdateMojo(RepositorySystem repositorySystem, org.eclipse.aether.RepositorySystem aetherRepositorySystem, Map<String, Wagon> wagonMap, Map<String, ChangeRecorder> changeRecorders) {
        super(repositorySystem, aetherRepositorySystem, wagonMap, changeRecorders);
    }

    @Override protected void update(ModifiedPomXMLEventReader pom) throws MojoExecutionException, MojoFailureException, XMLStreamException {
        try {
            UpdateChecker checker = new UpdateChecker(message -> getLog().info(message),
                // TODO use repos.stream().map(MavenArtifactRepository::getUrl).collect(Collectors.toList()) if UpdateChecker.loadVersions is fixed to exclude snapshots and pass authentication
                Arrays.asList("https://repo.jenkins-ci.org/releases/", "https://repo.jenkins-ci.org/incrementals/"));
            if (isProcessingDependencyManagement()) {
                DependencyManagement dependencyManagement = getProject().getDependencyManagement();
                if (dependencyManagement != null) {
                    update(pom, dependencyManagement.getDependencies(), checker);
                }
            }
            if (isProcessingDependencies()) {
                List<Dependency> dependencies = getProject().getDependencies();
                if (dependencies != null) {
                    update(pom, dependencies, checker);
                }
            }
            updateProperties(pom, checker);
        } catch (MojoExecutionException | MojoFailureException | XMLStreamException x) {
            throw x;
        } catch (Exception x) {
            throw new MojoExecutionException("Update failed", x);
        }
    }

    private void update(ModifiedPomXMLEventReader pom, List<Dependency> dependencies, UpdateChecker checker) throws Exception {
        for (Dependency dep : dependencies) {
            Artifact art = toArtifact(dep);
            if (!isIncluded(art)) {
                getLog().debug("Skipping " + toString(dep));
                continue;
            }
            if (isExcludeReactor() && isProducedByReactor(dep)) {
                getLog().info("Skipping reactor dep " + toString(dep));
                continue;
            }
            String version = dep.getVersion();
            if (ArtifactUtils.isSnapshot(version)) {
                getLog().info("Skipping snapshot dep " + toString(dep));
                continue;
            }
            if (!updateNonincremental && !isIncremental(version)) {
                getLog().debug("Skipping nonincremental dep " + toString(dep));
                continue;
            }
            String groupId = art.getGroupId();
            String artifactId = art.getArtifactId();
            // TODO need to add a caching layer here, as it can be called repeatedly with the same arguments in a reactor build
            UpdateChecker.VersionAndRepo result = checker.find(groupId, artifactId, version, branch);
            if (result == null) {
                getLog().info("No update found for " + toString(dep));
            } else {
                getLog().info("Can update dependency " + toString(dep) + " to " + result.version);
                PomHelper.setDependencyVersion(pom, groupId, artifactId, version, result.version.toString(), getProject().getModel());
            }
        }
    }

    private void updateProperties(ModifiedPomXMLEventReader pom, UpdateChecker checker) throws Exception {
        PROPERTY: for (Map.Entry<Property, PropertyVersions> entry : getHelper().getVersionPropertiesMap(VersionsHelper.VersionPropertiesMapRequest.builder().withMavenProject(getProject()).build()).entrySet()) {
            Property property = entry.getKey();
            String name = property.getName();
            PropertyVersions versions = entry.getValue();
            String version = getProject().getProperties().getProperty(name);
            if (version == null) {
                continue;
            }
            if (!updateNonincremental && !isIncremental(version)) {
                getLog().debug("Skipping nonincremental ${" + name + "}=" + version);
                continue;
            }
            List<String> ga = null; // [groupId, artifactId]
            for (ArtifactAssociation assn : versions.getAssociations()) {
                Artifact art = assn.getArtifact();
                if (!isIncluded(art)) {
                    getLog().info("Skipping update of ${" + name + "} because it is used in excluded " + art);
                    continue PROPERTY;
                }
                List<String> candidateGA = Arrays.asList(art.getGroupId(), art.getArtifactId());
                if (ga != null && !ga.equals(candidateGA)) {
                    // PropertyVersions.getNewestVersion is much fancier but we can anyway only search in one GA.
                    getLog().info("Skipping update of ${" + name + "} because it is used in both " + ga + " and " + candidateGA);
                    continue PROPERTY;
                }
                ga = candidateGA;
            }
            if (ga == null) {
                getLog().info("No artifacts using ${" + name + "}, skipping");
                continue;
            }
            UpdateChecker.VersionAndRepo result = checker.find(ga.get(0), ga.get(1), version, branch);
            if (result == null) {
                getLog().info("No update found for: " + ga.get(0) + ":" + ga.get(1) + ":" + version);
            } else {
                getLog().info("Can update ${" + name + "} to " + result.version);
                PomHelper.setPropertyVersion(pom, versions.getProfileId(), name, result.version.toString());
            }
        }
    }

    private static boolean isIncremental(String version) {
        return version.matches(".+-rc[0-9]+[.][0-9a-f]{12}");
    }

}
