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

import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import static org.twdata.maven.mojoexecutor.MojoExecutor.*;

/**
 * Switches project versions back to {@code ${revision}${changelist}} after using {@code maven-release-plugin}.
 */
@Mojo(name = "reincrementalify", requiresDirectInvocation = true, aggregator = true)
public class ReincrementalifyMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", required = true, readonly = true)
    private MavenSession mavenSession;

    @Component
    private BuildPluginManager pluginManager;

    @Override public void execute() throws MojoExecutionException, MojoFailureException {
        String version = project.getVersion();
        Properties properties = project.getProperties();
        String noop = "NOOP";
        String revision = properties.getProperty("revision", noop);
        String changelist = properties.getProperty("changelist", noop);
        if (noop.equals(revision) || noop.equals(changelist)) {
            throw new MojoExecutionException(this,
                "Cannot use reincrementalify before incrementals is enabled",
                "Please use 'incrementals:incrementalify' to enable incrementals");
        }
        Matcher m = Pattern.compile("(.+)-SNAPSHOT").matcher(version);
        if (!m.matches()) {
            throw new MojoFailureException("Unexpected version: " + version);
        }
        properties.setProperty("dollar", "$");
        executeMojo(plugin("org.codehaus.mojo", "versions-maven-plugin", "2.20.1"), "set",
            configuration(
                element("newVersion", "${dollar}{revision}${dollar}{changelist}"),
                element("generateBackupPoms", "false")),
            executionEnvironment(project, mavenSession, pluginManager));
        executeMojo(plugin("org.codehaus.mojo", "versions-maven-plugin", "2.20.1"), "set-property",
            configuration(
                element("property", "revision"),
                element("newVersion", m.group(1)),
                element("generateBackupPoms", "false")),
            executionEnvironment(project, mavenSession, pluginManager));
    }

}
