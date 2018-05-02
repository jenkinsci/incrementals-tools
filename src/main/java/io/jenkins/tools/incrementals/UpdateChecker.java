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

package io.jenkins.tools.incrementals;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import javax.annotation.CheckForNull;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.maven.artifact.versioning.ComparableVersion;
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

    public static @CheckForNull String find(String groupId, String artifactId, String currentVersion, String branch, List<String> repos, Log log) throws Exception {
        ComparableVersion currentV = new ComparableVersion(currentVersion);
        SortedSet<ComparableVersion> candidates = loadVersions(groupId, artifactId, repos);
        log.info("Candidates: " + candidates);
        for (ComparableVersion candidate : candidates) {
            if (candidate.compareTo(currentV) <= 0) {
                log.info("Stopping search at " + candidate + " since it is no newer than " + currentV);
                return null;
            }
            log.info("Considering " + candidate);
        }
        return null;
    }

    private static SortedSet<ComparableVersion> loadVersions(String groupId, String artifactId, List<String> repos) throws Exception {
        // TODO consider using official Aether APIs here (could make use of local cache)
        SortedSet<ComparableVersion> r = new TreeSet<>(Comparator.reverseOrder());
        for (String repo : repos) {
            String mavenMetadataURL = repo + groupId.replace('.', '/') + '/' + artifactId + "/maven-metadata.xml";
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(mavenMetadataURL);
            NodeList versionsE = doc.getElementsByTagName("versions");
            if (versionsE.getLength() != 1) {
                throw new Exception("Cannot find <version> in " + mavenMetadataURL);
            }
            NodeList versionEs = ((Element) versionsE.item(0)).getElementsByTagName("version");
            for (int i = 0; i < versionEs.getLength(); i++) {
                r.add(new ComparableVersion(versionEs.item(i).getTextContent()));
            }
        }
        return r;
    }

    public static void main(String... argv) throws Exception {
        if (argv.length != 4) {
            throw new IllegalStateException("Usage: java " + UpdateChecker.class.getName() + " <groupId> <artifactId> <currentVersion> <branch>");
        }
        String result = find(argv[0], argv[1], argv[2], argv[3],
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
