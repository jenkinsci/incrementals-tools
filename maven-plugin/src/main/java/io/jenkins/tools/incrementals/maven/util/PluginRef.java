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
package io.jenkins.tools.incrementals.maven.util;

import org.apache.maven.model.Dependency;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

/**
 * PluginRef class for plugins.txt.
 * This reference can be either a comment line or a plugin definition.
 * @author Oleg Nenashev
 * @since TODO
 */
public class PluginRef {

    private @CheckForNull String artifactId;
    private @CheckForNull String groupId;
    private @CheckForNull String tag;
    private @CheckForNull String version;
    private boolean incrementals;
    private @CheckForNull String comment;
    // plugins.txt does not support githubBranch on its own, but we can add it as additional metadata
    private @CheckForNull String githubUser;
    private @CheckForNull String githubBranch;

    private PluginRef() {}

    @CheckForNull
    public String getArtifactId() {
        return artifactId;
    }

    @CheckForNull
    public String getGroupId() {
        return groupId;
    }

    @CheckForNull
    public String getTag() {
        return tag;
    }

    @CheckForNull
    public String getVersion() {
        return version;
    }

    public boolean isIncrementals() {
        return incrementals;
    }

    @CheckForNull
    public String getComment() {
        return comment;
    }

    public boolean isComment() {
        return comment != null;
    }

    @CheckForNull
    public String getGithubUser() {
        return githubUser;
    }

    @CheckForNull
    public String getGithubBranch() {
        return githubBranch;
    }

    @Override
    public String toString() {
        if (comment != null) {
            return "comment: " + comment;
        }
        return artifactId + (version != null ? (":" + version) : "");
    }

    @NonNull
    public String toPluginsTxtString() {
        if (comment != null) {
            return comment;
        }

        if (isIncrementals()) {
            String res = String.format("%s:%s;%s;%s", artifactId, "incrementals", groupId, version);
            if (githubUser != null) {
                res += ";" + githubUser;
            }
            if (githubBranch != null) {
                res += ";" + githubBranch;
            }
            return res;
        }

        String label = version != null ? version : tag;
        return artifactId + (label != null ? (":" + label) : "");
    }

    @NonNull
    public static PluginRef forComment(@NonNull String line) throws IOException {
        PluginRef ref = new PluginRef();
        ref.comment = line;
        return ref;
    }

    public void setVersion(@NonNull String version) {
        this.version = version;
    }

    @NonNull
    public static PluginRef fromString(@NonNull String str) throws IOException {
        String[] entries = str.split(":");
        if (entries.length == 0) {
            throw new IOException("Version string is corrupted: " + str);
        }

        PluginRef plugin = new PluginRef();
        plugin.artifactId = entries[0];

        if (entries.length > 1) {
            String[] versionEntries = entries[1].split(";");
            switch (versionEntries[0]) {
                case "latest":
                    plugin.tag = "latest";
                    break; // do nothing
                case "experimental":
                    plugin.tag = "experimental";
                    break; // do nothing, not supported in the tool
                case "incrementals":
                    // incrementals;org.jenkins-ci.plugins.workflow;2.19-rc289.d09828a05a74;oleg-nenashev;my-branch
                    // last 2 fields are optional
                    if (versionEntries.length < 3 || versionEntries.length > 5) {
                        throw new IOException("Wrong incrementals format: " + str);
                    }
                    plugin.groupId = versionEntries[1];
                    plugin.version = versionEntries[2];
                    if (versionEntries.length == 4) {
                        plugin.githubBranch = versionEntries[3];
                    } else if (versionEntries.length == 5) {
                        plugin.githubUser = versionEntries[3];
                        plugin.githubBranch = versionEntries[4];
                    }
                    plugin.incrementals = true;
                    break;
                default:
                    plugin.version = versionEntries[0];
            }
        }
        return plugin;
    }

    @NonNull
    public Dependency toDependency() {
        Dependency dep = new Dependency();
        dep.setArtifactId(artifactId);
        dep.setGroupId(groupId);
        dep.setVersion(version);
        return dep;
    }
 }
