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

package io.jenkins.tools.incrementals.lib;

import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.CheckForNull;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.kohsuke.github.GHCompare;
import org.kohsuke.github.GitHub;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Looks for updates (incremental or otherwise) to a specific artifact.
 */
public class UpdateChecker {

    @FunctionalInterface
    public interface Log {
        void info(String message);
    }

    public static final class VersionAndRepo implements Comparable<VersionAndRepo> {
        public final String groupId;
        public final String artifactId;
        public final ComparableVersion version;
        public final String repo;
        VersionAndRepo(String groupId, String artifactId, ComparableVersion version, String repo) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.repo = repo;
        }
        /** Sort by version descending. */
        @Override public int compareTo(VersionAndRepo o) {
            assert o.groupId.equals(groupId) && o.artifactId.equals(artifactId);
            return o.version.compareTo(version);
        }
        /** @return for example: {@code https://repo/net/nowhere/lib/1.23/} */
        public String baseURL() {
            return repo + groupId.replace('.', '/') + '/' + artifactId + '/' + version + '/';
        }
        /**
         * @param type for example, {@code pom}
         * @return for example: {@code https://repo/net/nowhere/lib/1.23/lib-1.23.pom}
         */
        public String fullURL(String type) {
            return baseURL() + artifactId + '-' + version + '.' + type;
        }
        @Override public String toString() {
            return baseURL();
        }
    }

    public static @CheckForNull VersionAndRepo find(String groupId, String artifactId, String currentVersion, String branch, List<String> repos, Log log) throws Exception {
        ComparableVersion currentV = new ComparableVersion(currentVersion);
        log.info("Searching for updates to " + groupId + ":" + artifactId + ":" + currentV + " within " + branch);
        SortedSet<VersionAndRepo> candidates = loadVersions(groupId, artifactId, repos);
        if (candidates.isEmpty()) {
            log.info("Found no candidates");
            return null;
        }
        log.info("Found " + candidates.size() + " candidates from " + candidates.first() + " down to " + candidates.last());
        for (VersionAndRepo candidate : candidates) {
            if (candidate.version.compareTo(currentV) <= 0) {
                log.info("Stopping search at " + candidate + " since it is no newer than " + currentV);
                return null;
            }
            log.info("Considering " + candidate);
            GitHubCommit ghc = loadGitHubCommit(candidate);
            if (ghc != null) {
                log.info("Mapped to: " + ghc);
                if (isAncestor(ghc, branch)) {
                    log.info("Seems to be within " + branch + ", so accepting");
                    return candidate;
                } else {
                    log.info("Does not seem to be within " + branch);
                }
            } else {
                log.info("Does not seem to be an incremental release, so accepting");
                // TODO may still be useful to select MRP versions targeted to an origin branch.
                // (For example, select the latest backport from a stable branch rather than trunk.)
                // The problem is that we cannot then guarantee that the POM has been flattened
                // (this is only guaranteed for repositories which *may* produce incrementals),
                // and loadGitHubCommit will not work for nonflattened POMs from reactor submodules:
                // it would have to be made more complicated to resolve the parent POM(s),
                // or we would need to switch the implementation to use Maven/Aether resolution APIs.
                return candidate;
            }
        }
        return null;
    }

    /**
     * Look for all known versions of a given artifact.
     * @param repos a set of repository URLs to check
     * @return a possibly empty set of versions, sorted descending
     */
    private static SortedSet<VersionAndRepo> loadVersions(String groupId, String artifactId, List<String> repos) throws Exception {
        // TODO consider using official Aether APIs here (could make use of local cache)
        SortedSet<VersionAndRepo> r = new TreeSet<>();
        for (String repo : repos) {
            String mavenMetadataURL = repo + groupId.replace('.', '/') + '/' + artifactId + "/maven-metadata.xml";
            Document doc;
            try {
                doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(mavenMetadataURL);
            } catch (FileNotFoundException x) {
                continue; // not even defined in this repo, fine
            }
            Element versionsE = theElement(doc, "versions", mavenMetadataURL);
            NodeList versionEs = versionsE.getElementsByTagName("version");
            for (int i = 0; i < versionEs.getLength(); i++) {
                // Not bothering to exclude timestamped snapshots for now, since we are working with release repositories anyway.
                r.add(new VersionAndRepo(groupId, artifactId, new ComparableVersion(versionEs.item(i).getTextContent()), repo));
            }
        }
        return r;
    }

    private static final class GitHubCommit {
        final String owner;
        final String repo;
        final String hash;
        GitHubCommit(String owner, String repo, String hash) {
            this.owner = owner;
            this.repo = repo;
            this.hash = hash;
        }
        @Override public String toString() {
            return "https://github.com/" + owner + '/' + repo + "/commit/" + hash;
        }
    }

    /**
     * Parses {@code /project/scm/url} and {@code /project/scm/tag} out of a POM, if mapped to a commit.
     */
    private static @CheckForNull GitHubCommit loadGitHubCommit(VersionAndRepo vnr) throws Exception {
        String pom = vnr.fullURL("pom");
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom);
        NodeList scmEs = doc.getElementsByTagName("scm");
        if (scmEs.getLength() != 1) {
            return null;
        }
        Element scmE = (Element) scmEs.item(0);
        Element urlE = theElement(scmE, "url", pom);
        String url = urlE.getTextContent();
        Matcher m = Pattern.compile("https?://github[.]com/([^/]+)/([^/]+?)([.]git)?(/.*)?").matcher(url);
        if (!m.matches()) {
            throw new Exception("Unexpected /project/scm/url " + url + " in " + pom + "; expecting https://github.com/owner/repo format");
        }
        Element tagE = theElement(scmE, "tag", pom);
        String tag = tagE.getTextContent();
        String groupId = m.group(1);
        String artifactId = m.group(2).replace("${project.artifactId}", vnr.artifactId);
        if (!tag.matches("[a-f0-9]{40}")) {
            return null;
        }
        return new GitHubCommit(groupId, artifactId, tag);
    }

    /**
     * Checks whether a commit is an ancestor of a given branch head.
     * {@code curl -s -u … https://api.github.com/repos/<owner>/<repo>/compare/<hash>...<branch> | jq -r .status}
     * will return {@code identical} or {@code ahead} if so, else {@code diverged} or {@code behind}.
     * @param branch may be {@code master} or {@code forker:branch}
     * @see <a href="https://developer.github.com/v3/repos/commits/#compare-two-commits">Compare two commits</a>
     */
    private static boolean isAncestor(GitHubCommit ghc, String branch) throws Exception {
        try {
            GHCompare.Status status = GitHub.connect().getRepository(ghc.owner + '/' + ghc.repo).getCompare(ghc.hash, branch).status;
            return status == GHCompare.Status.identical || status == GHCompare.Status.ahead;
        } catch (FileNotFoundException x) {
            // For example, that branch does not exist in this repository.
            return false;
        }
    }

    private static Element theElement(Document doc, String tagName, String url) throws Exception {
        return theElement(doc.getElementsByTagName(tagName), tagName, url);
    }

    private static Element theElement(Element parent, String tagName, String url) throws Exception {
        return theElement(parent.getElementsByTagName(tagName), tagName, url);
    }

    private static Element theElement(NodeList nl, String tagName, String url) throws Exception {
        if (nl.getLength() != 1) {
            throw new Exception("Could not find <" + tagName + "> in " + url);
        }
        return (Element) nl.item(0);
    }

    public static void main(String... argv) throws Exception {
        if (argv.length != 4) {
            throw new IllegalStateException("Usage: java " + UpdateChecker.class.getName() + " <groupId> <artifactId> <currentVersion> <branch>");
        }
        VersionAndRepo result = find(argv[0], argv[1], argv[2], argv[3],
            Arrays.asList("https://repo.jenkins-ci.org/releases/", "https://repo.jenkins-ci.org/incrementals/"),
            message -> System.err.println(message));
        if (result != null) {
            System.err.println("Found: " + result);
        } else {
            System.err.println("Nothing found.");
        }
    }

    private UpdateChecker() {}

}
