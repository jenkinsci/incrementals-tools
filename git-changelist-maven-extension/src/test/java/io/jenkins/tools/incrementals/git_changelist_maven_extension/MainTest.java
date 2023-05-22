/*
 * The MIT License
 *
 * Copyright 2021 CloudBees, Inc.
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

import org.apache.maven.artifact.versioning.ComparableVersion;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class MainTest {

    // https://maven.apache.org/pom.html#Version_Order_Specification
    private static final String[] PRERELEASE = {
        // From ComparableVersion.StringItem.QUALIFIERS:
        "alpha", "beta", "milestone", "rc", "snapshot",
        // ALIASES:
        "cr",
        // Nonstandard ones in Dependabot? https://github.com/dependabot/dependabot-core/blob/f146743aa400c7913b5e953e1b93c8b40345aaf4/maven/lib/dependabot/maven/version.rb#L24-L25
        "pr", "dev",
    };

    @Test
    public void alphaBeta() {
        String hash = "852b473a2b8c";
        String sanitized = Main.sanitize(hash);
        assertThat(hash + " has been sanitized to the expected format", sanitized, is("852b_473a_2b_8c"));
        String canonical = new ComparableVersion(sanitized).getCanonical();
        for (String prerelease : PRERELEASE) {
            assertThat(sanitized + " treated as a prerelease", canonical, not(containsString(prerelease)));
        }
    }

    @Test public void alphaBetaTrailing() {
        String hash = "852b473a2bcb";
        String sanitized = Main.sanitize(hash);
        assertThat(hash + " has been sanitized to the expected format", sanitized, is("852b_473a_2b_cb_"));
        String canonical = new ComparableVersion(sanitized).getCanonical();
        for (String prerelease : PRERELEASE) {
            assertThat(sanitized + " treated as a prerelease", canonical, not(containsString(prerelease)));
        }
    }
}
