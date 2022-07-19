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

package io.jenkins.tools.incrementals.git_changelist_maven_extension;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * Sets a {@code changelist} property to a value based on the Git checkout.
 * {@code -Dset.changelist} then becomes equivalent to:
 * {@code -Dchangelist=-rc$(git rev-list --count HEAD).$(git rev-parse --short=12 HEAD)}
 * <p>Also does the equivalent of: {@code -DscmTag=$(git rev-parse HEAD)}
 * @see <a href="https://maven.apache.org/maven-ci-friendly.html">Maven CI Friendly Versions</a>
 * @see <a href="https://maven.apache.org/docs/3.3.1/release-notes.html#Core_Extensions">Core Extensions</a>
 */
@Component(role=AbstractMavenLifecycleParticipant.class, hint="git-changelist-maven-extension")
public class Main extends AbstractMavenLifecycleParticipant {

    private static final String IGNORE_DIRT = "ignore.dirt";
    private static final int ABBREV_LENGTH = 12;

    @Requirement
    private Logger log;

    @Override
    public void afterSessionStart(MavenSession session) throws MavenExecutionException {
        Properties props = session.getRequest().getUserProperties();
        if ("true".equals(props.getProperty("set.changelist"))) {
            if (!props.containsKey("changelist") && !props.containsKey("scmTag")) {
                long start = System.nanoTime();
                File dir = session.getRequest().getMultiModuleProjectDirectory();
                log.debug("running in " + dir);
                String fullHash, hash;
                int count;
                try (Git git = Git.open(dir)) {
                    Status status = git.status().call();
                    if (!status.isClean()) {
                        // Could consider instead making this append a timestamp baased on the most recent file modification.
                        Set<String> paths = new TreeSet<>(status.getUncommittedChanges());
                        paths.addAll(status.getUntracked());
                        String error = "Make sure `git status -s` is empty before using -Dset.changelist: " + paths;
                        // Note that `git st` does not care about untracked _folders_ so long as there are no relevant _files_ inside them.
                        if ("true".equals(props.getProperty(IGNORE_DIRT))) {
                            log.warn(error);
                        } else {
                            throw new MavenExecutionException(error + " (use -D" + IGNORE_DIRT + " to make this nonfatal)", (Throwable) null);
                        }
                    }
                    Repository repo = git.getRepository();
                    ObjectId head = repo.resolve("HEAD");
                    fullHash = head.name();
                    hash = head.abbreviate(ABBREV_LENGTH).name();
                    try (RevWalk walk = new RevWalk(repo)) {
                        RevCommit headC = walk.parseCommit(head);
                        count = revCount(walk, headC);
                        { // Look for repository commits reachable from HEAD that would clash.
                            Map<String,List<RevCommit>> encountered = new HashMap<>();
                            walk.markStart(headC);
                            int commitCount = 0;
                            for (RevCommit c : walk) {
                                commitCount++;
                                String abbreviated = c.getId().abbreviate(ABBREV_LENGTH).name();
                                List<RevCommit> earlier = encountered.get(abbreviated);
                                if (earlier == null) {
                                    earlier = new ArrayList<>(1);
                                    earlier.add(c);
                                    encountered.put(abbreviated, earlier);
                                } else {
                                    int thisCount = revCount(walk, c);
                                    for (RevCommit other : earlier) {
                                        int otherCount = revCount(walk, other);
                                        if (otherCount == thisCount) {
                                            throw new MavenExecutionException(summarize(c) + " clashes with " + summarize(other) + " as they would both be identified as " + thisCount + "." + abbreviated, (Throwable) null);
                                        } else {
                                            log.info(summarize(c) + " would clash with " + summarize(other) + " except they have differing revcounts: " + thisCount + " vs. " + otherCount);
                                        }
                                    }
                                }
                            }
                            log.debug("Analyzed " + commitCount + " commits for clashes");
                        }
                    }
                } catch (IOException | GitAPIException x) {
                    throw new MavenExecutionException("Git operations failed", x);
                }
                log.debug("Spent " + (System.nanoTime() - start) / 1000 / 1000 + "ms on calculations");
                String value = String.format(props.getProperty("changelist.format", "-rc%d.%s"), count, sanitize(hash));
                log.info("Setting: -Dchangelist=" + value + " -DscmTag=" + fullHash);
                props.setProperty("changelist", value);
                props.setProperty("scmTag", fullHash);
            } else {
                log.info("Declining to override the `changelist` or `scmTag` properties");
            }
            if (!props.contains("gitHubRepo")) {
                String gitHubRepo;
                String changeFork = System.getenv("CHANGE_FORK");
                if (changeFork == null) {
                    log.info("No information available to set -DgitHubRepo");
                    gitHubRepo = null;
                } else if (changeFork.contains("/")) {
                    gitHubRepo = changeFork;
                } else {
                    String jobName = System.getenv("JOB_NAME");
                    if (jobName == null) {
                        log.info("CHANGE_FORK set but incomplete without JOB_NAME");
                        gitHubRepo = null;
                    } else {
                        String[] pieces = jobName.split("/");
                        if (pieces.length >= 2) { // e.g. Plugins/build-token-root-plugin/PR-21
                            gitHubRepo = changeFork + "/" + pieces[pieces.length - 2]; // e.g. jglick/build-token-root-plugin
                        } else {
                            log.info("CHANGE_FORK set but incomplete and JOB_NAME also incomplete");
                            gitHubRepo = null;
                        }
                    }
                }
                if (gitHubRepo != null) {
                    log.info("Setting: -DgitHubRepo=" + gitHubRepo);
                    props.setProperty("gitHubRepo", gitHubRepo);
                }
            } else {
                log.info("Declining to override the `gitHubRepo` property");
            }
        } else {
            log.debug("Skipping Git version setting unless run with -Dset.changelist");
        }
    }

    static String sanitize(String hash) {
        return hash.replaceAll("[ab]", "$0_").replaceAll("_$", "");
    }

    private static String summarize(RevCommit c) {
        return c.getId().name() + " “" + c.getShortMessage() + "” " + DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochSecond(c.getCommitTime()).atZone(ZoneId.systemDefault()));
    }

    private static int revCount(RevWalk walk, RevCommit c) throws IOException, GitAPIException {
        int count = 0;
        try (RevWalk walk2 = new RevWalk(walk.getObjectReader())) {
            walk2.markStart(walk2.parseCommit(c));
            for (RevCommit c2 : walk2) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        Properties props = session.getRequest().getUserProperties();
        if ("true".equals(props.getProperty("set.changelist"))) {
            String changelist = props.getProperty("changelist");
            for (MavenProject project : session.getProjects()) {
                String version = project.getVersion();
                if (!version.contains(changelist)) {
                    log.warn(project.getId() + " does not seem to be including ${changelist} in its <version>");
                }
            }
        }
    }

}
