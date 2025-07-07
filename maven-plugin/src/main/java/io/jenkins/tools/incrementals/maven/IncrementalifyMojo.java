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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.xml.stream.XMLStreamException;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.wagon.Wagon;
import org.codehaus.mojo.versions.AbstractVersionsUpdaterMojo;
import org.codehaus.mojo.versions.api.ArtifactVersions;
import org.codehaus.mojo.versions.api.PomHelper;
import org.codehaus.mojo.versions.api.VersionRetrievalException;
import org.codehaus.mojo.versions.api.VersionsHelper;
import org.codehaus.mojo.versions.api.recording.ChangeRecorder;
import org.codehaus.mojo.versions.rewriting.MutableXMLStreamReader;
import org.eclipse.aether.RepositorySystem;
import static org.twdata.maven.mojoexecutor.MojoExecutor.*;

/**
 * Sets a project up for Incrementals.
 */
@Mojo(name = "incrementalify", requiresDirectInvocation = true, aggregator = true)
public class IncrementalifyMojo extends AbstractVersionsUpdaterMojo {

    private static final String MINIMUM_JENKINS_PARENT = "1.47";
    private static final String MINIMUM_PLUGIN_PARENT = "3.10";
    public static final String JENKINS_POM = "org.jenkins-ci:jenkins:pom";
    public static final String PLUGIN_POM = "org.jenkins-ci.plugins:plugin:pom";
    private static final Set<String> PARENT_DEPENDENCIES = Set.of(JENKINS_POM, PLUGIN_POM);

    @Component
    private BuildPluginManager pluginManager;

    @Inject public IncrementalifyMojo(ArtifactHandlerManager artifactHandlerManager, RepositorySystem repositorySystem, Map<String, Wagon> wagonMap, Map<String, ChangeRecorder> changeRecorders) {
        super(artifactHandlerManager, repositorySystem, wagonMap, changeRecorders);
    }

    @Override public void execute() throws MojoExecutionException, MojoFailureException {
        File dotMvn = new File(project.getBasedir(), ".mvn");
        File extensionsXml = new File(dotMvn, "extensions.xml");
        if (extensionsXml.isFile()) {
            throw new MojoFailureException("Editing an existing " + extensionsXml + " is not yet supported");
        }
        ArtifactVersions gclmeVersions;
        try {
            gclmeVersions = getHelper().lookupArtifactVersions(getHelper().createDependencyArtifact("io.jenkins.tools.incrementals", "git-changelist-maven-extension", "[0,)", "type", null, null, false), true);
        } catch (VersionRetrievalException x) {
            throw new MojoExecutionException(x.getMessage(), x);
        }
        VersionRange any;
        try {
            any = VersionRange.createFromVersionSpec("[0,)");
        } catch (InvalidVersionSpecificationException x) {
            throw new MojoExecutionException(x.getMessage(), x);
        }
        ArtifactVersion gclmeNewestVersion = gclmeVersions.getNewestVersion(any, false);
        super.execute();
        project.getProperties().setProperty("dollar", "$");
        executeMojo(plugin("org.codehaus.mojo", "versions-maven-plugin", "2.5"), "set",
            configuration(
                element("newVersion", "${dollar}{revision}${dollar}{changelist}"),
                element("generateBackupPoms", "false")),
            executionEnvironment(project, session, pluginManager));
        File mavenConfig = new File(dotMvn, "maven.config");
        try {
            String existing = mavenConfig.isFile() ? Files.readString(mavenConfig.toPath(), StandardCharsets.UTF_8) : "";
            dotMvn.mkdirs();
            Files.writeString(mavenConfig.toPath(), "-Pconsume-incrementals\n-Pmight-produce-incrementals\n" + existing, StandardCharsets.UTF_8);
            try (InputStream is = IncrementalifyMojo.class.getResourceAsStream("prototype-extensions.xml")) {
                Files.writeString(extensionsXml.toPath(), new String(is.readAllBytes(), StandardCharsets.UTF_8).replace("@VERSION@", gclmeNewestVersion.toString()), StandardCharsets.UTF_8);
            }
        } catch (IOException x) {
            throw new MojoExecutionException("failed to update " + dotMvn, x);
        }
    }

    @Override protected void update(MutableXMLStreamReader pom) throws MojoExecutionException, MojoFailureException, XMLStreamException {
        String version = PomHelper.getProjectVersion(pom);
        Matcher m = Pattern.compile("(.+)-SNAPSHOT").matcher(version);
        if (!m.matches()) {
            throw new MojoFailureException("Unexpected version: " + version);
        }
        String origTag = project.getScm().getTag();
        if (!origTag.equals("HEAD")) {
            throw new MojoFailureException("Unexpected tag: " + origTag);
        }
        Artifact parent = getProjectParent(pom, getHelper());
        if (parent == null) {
            throw new MojoFailureException("No <parent> found");
        }
        if (!PARENT_DEPENDENCIES.contains(parent.getDependencyConflictId())) {
            throw new MojoFailureException("Unexpected <parent> " + parent);
        }
        String connection = project.getScm().getConnection();
        String developerConnection = project.getScm().getDeveloperConnection();
        String url = project.getScm().getUrl();
        if (connection == null || developerConnection == null || url == null) {
            throw new MojoFailureException("<scm> must contain all of connection, developerConnection, and url");
        }
        ReplaceGitHubRepo connectionRGHR = replaceGitHubRepo(connection);
        ReplaceGitHubRepo developerConnectionRGHR = replaceGitHubRepo(developerConnection);
        ReplaceGitHubRepo urlRGHR = replaceGitHubRepo(url);
        if (!developerConnectionRGHR.gitHubRepo.equals(connectionRGHR.gitHubRepo) || !urlRGHR.gitHubRepo.equals(connectionRGHR.gitHubRepo)) {
            throw new MojoFailureException("Mismatch among gitHubRepo parts of <scm>: " + connectionRGHR.gitHubRepo + " vs. " + developerConnectionRGHR.gitHubRepo + " vs. " + urlRGHR.gitHubRepo);
        }
        String minimum_parent;
        if (parent.getDependencyConflictId().equals(JENKINS_POM)) {
            minimum_parent = MINIMUM_JENKINS_PARENT;
        } else {
            minimum_parent = MINIMUM_PLUGIN_PARENT;
        }
        if (new ComparableVersion(parent.getVersion()).compareTo(new ComparableVersion(minimum_parent)) < 0) {
            PomHelper.setProjectParentVersion(pom, minimum_parent);
        }
        prependProperty(pom, "gitHubRepo", connectionRGHR.gitHubRepo);
        prependProperty(pom, "changelist", "-SNAPSHOT");
        prependProperty(pom, "revision", m.group(1));
        PomHelper.setElementValue(pom, "/project/scm", "tag", "${scmTag}");
        PomHelper.setElementValue(pom, "/project/scm", "connection", connectionRGHR.interpolableText);
        PomHelper.setElementValue(pom, "/project/scm", "developerConnection", developerConnectionRGHR.interpolableText);
        PomHelper.setElementValue(pom, "/project/scm", "url", urlRGHR.interpolableText);
    }

    /**
     * Gets the parent artifact from the pom.
     *
     * @param pom    The pom.
     * @param helper The helper (used to create the artifact).
     * @return The parent artifact or <code>null</code> if no parent is specified.
     * @throws XMLStreamException if something went wrong.
     */
    private static Artifact getProjectParent( final MutableXMLStreamReader pom, VersionsHelper helper )
            throws XMLStreamException
    {
        Stack<String> stack = new Stack<>();
        String path = "";
        final Pattern matchScopeRegex = Pattern.compile( "/project/parent((/groupId)|(/artifactId)|(/version))" );
        String groupId = null;
        String artifactId = null;
        String version = null;

        pom.rewind();

        while ( pom.hasNext() )
        {
            pom.next();
            if ( pom.isStartElement() )
            {
                stack.push( path );
                final String elementName = pom.getLocalName();
                path = path + "/" + elementName;

                if ( matchScopeRegex.matcher( path ).matches() )
                {
                    if ( "groupId".equals( elementName ) )
                    {
                        groupId = pom.getElementText().trim();
                        path = stack.pop();
                    }
                    else if ( "artifactId".equals( elementName ) )
                    {
                        artifactId = pom.getElementText().trim();
                        path = stack.pop();
                    }
                    else if ( "version".equals( elementName ) )
                    {
                        version = pom.getElementText().trim();
                        path = stack.pop();
                    }
                }
            }
            if ( pom.isEndElement() )
            {
                path = stack.pop();
            }
        }
        if ( groupId == null || artifactId == null || version == null )
        {
            return null;
        }
        return helper.createDependencyArtifact( groupId, artifactId, version, "pom",
                null, null, false );
    }

    private static final class ReplaceGitHubRepo {
        final String interpolableText;
        final String gitHubRepo;
        ReplaceGitHubRepo(String interpolableText, String gitHubRepo) {
            this.interpolableText = interpolableText;
            this.gitHubRepo = gitHubRepo;
        }
    }
    private static final Pattern TEXT = Pattern.compile("(.+[:/])((?:[^/]+)/(?:[^/]+?))((?:[.]git)?)");
    static ReplaceGitHubRepo replaceGitHubRepo(String text) throws MojoFailureException {
        Matcher m = TEXT.matcher(text);
        if (!m.matches()) {
            throw new MojoFailureException(text + " did not match " + TEXT);
        }
        return new ReplaceGitHubRepo(m.group(1) + "${gitHubRepo}" + m.group(3), m.group(2));
    }

    private void prependProperty(MutableXMLStreamReader pom, String name, String value) throws XMLStreamException, MojoFailureException {
        Stack<String> stack = new Stack<>();
        pom.rewind();
        boolean found = false;
        while (pom.hasNext()) {
            pom.next();
            if (pom.isStartElement()) {
                stack.push(pom.getLocalName());
                if (stack.equals(Arrays.asList("project", "properties"))) {
                    pom.mark(0);
                }
            } else if (pom.isEndElement()) {
                if (stack.equals(Arrays.asList("project", "properties"))) {
                    pom.mark(1);
                    found = true;
                    String orig = pom.getBetween(0, 1);
                    DetectIndent.Indent indent = new DetectIndent().detect(orig);
                    pom.replaceBetween(0, 1, "\n" + indent.getIndent() + "<" + name + ">" + value + "</" + name + ">" + orig);
                    pom.clearMark(0);
                    pom.clearMark(1);
                }
                stack.pop();
            }
        }
        if (!found) {
            throw new MojoFailureException("failed to find <properties>");
        }
    }

}
