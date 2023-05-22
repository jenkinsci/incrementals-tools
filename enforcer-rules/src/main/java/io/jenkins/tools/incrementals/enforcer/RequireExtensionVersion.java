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

package io.jenkins.tools.incrementals.enforcer;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Named;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.enforcer.rule.api.AbstractEnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.util.StringUtils;

/**
 * Verifies that {@code git-changelist-maven-extension}, if present, is sufficiently new.
 */
@Named("requireExtensionVersion")
public class RequireExtensionVersion extends AbstractEnforcerRule {

    private static final Pattern ID_PATTERN = Pattern.compile("\\QcoreExtension>io.jenkins.tools.incrementals:git-changelist-maven-extension:\\E(.+)");

    /**
     * Specify the required version. Some examples are:
     * <ul>
     * <li><code>2.0.4</code> Version 2.0.4 and higher (different from Maven meaning)</li>
     * <li><code>[2.0,2.1)</code> Versions 2.0 (included) to 2.1 (not included)</li>
     * <li><code>[2.0,2.1]</code> Versions 2.0 to 2.1 (both included)</li>
     * <li><code>[2.0.5,)</code> Versions 2.0.5 and higher</li>
     * <li><code>(,2.0.5],[2.1.1,)</code> Versions up to 2.0.5 (included) and 2.1.1 or higher</li>
     * </ul>
     *
     * @see #setVersion(String)
     * @see #getVersion()
     */
    private String version;

    private final PlexusContainer container;

    @Inject
    public RequireExtensionVersion(PlexusContainer container) {
        this.container = Objects.requireNonNull(container);
    }

    @Override
    public void execute() throws EnforcerRuleException {
        List<AbstractMavenLifecycleParticipant> participants;
        try {
            participants = container.lookupList(AbstractMavenLifecycleParticipant.class);
        } catch (ComponentLookupException x) {
            getLog().warn(x.getMessage());
            return;
        }
        for (AbstractMavenLifecycleParticipant participant : participants) {
            // TODO is there some better way of identifying a class loaded by Maven?
            ClassLoader loader = participant.getClass().getClassLoader();
            if (loader instanceof ClassRealm) {
                Matcher m = ID_PATTERN.matcher(((ClassRealm) loader).getId());
                if (m.matches()) {
                    enforceVersion("git-changelist-maven-extension", getVersion(), new DefaultArtifactVersion(m.group(1)));
                }
            }
        }
    }

    /**
     * Compares the specified version to see if it is allowed by the defined version range.
     *
     * @param variableName         name of variable to use in messages (Example: "Maven" or "Java" etc).
     * @param requiredVersionRange range of allowed versions.
     * @param actualVersion        the version to be checked.
     * @throws EnforcerRuleException the enforcer rule exception
     */
    // CHECKSTYLE_OFF: LineLength
    private void enforceVersion(String variableName, String requiredVersionRange, ArtifactVersion actualVersion)
            throws EnforcerRuleException
    // CHECKSTYLE_ON: LineLength
    {
        if (StringUtils.isEmpty(requiredVersionRange)) {
            throw new EnforcerRuleException(variableName + " version can't be empty.");
        } else {

            VersionRange vr;
            String msg = "Detected " + variableName + " Version: " + actualVersion;

            // short circuit check if the strings are exactly equal
            if (actualVersion.toString().equals(requiredVersionRange)) {
                getLog().debug(msg + " is allowed in the range " + requiredVersionRange + ".");
            } else {
                try {
                    vr = VersionRange.createFromVersionSpec(requiredVersionRange);

                    if (containsVersion(vr, actualVersion)) {
                        getLog().debug(msg + " is allowed in the range " + toString(vr) + ".");
                    } else {
                        throw new EnforcerRuleException(msg + " is not in the allowed range " + toString(vr) + ".");
                    }
                } catch (InvalidVersionSpecificationException e) {
                    throw new EnforcerRuleException(
                            "The requested " + variableName + " version " + requiredVersionRange + " is invalid.", e);
                }
            }
        }
    }

    /**
     * Copied from Artifact.VersionRange. This is tweaked to handle singular ranges properly. Currently the default
     * containsVersion method assumes a singular version means allow everything. This method assumes that "2.0.4" ==
     * "[2.0.4,)"
     *
     * @param allowedRange range of allowed versions.
     * @param theVersion   the version to be checked.
     * @return true if the version is contained by the range.
     */
    private static boolean containsVersion(VersionRange allowedRange, ArtifactVersion theVersion) {
        ArtifactVersion recommendedVersion = allowedRange.getRecommendedVersion();
        if (recommendedVersion == null) {
            return allowedRange.containsVersion(theVersion);
        } else {
            // only singular versions ever have a recommendedVersion
            int compareTo = recommendedVersion.compareTo(theVersion);
            return (compareTo <= 0);
        }
    }

    private static String toString(VersionRange vr) {
        // as recommended version is used as lower bound in this context modify the string representation
        if (vr.getRecommendedVersion() != null) {
            return "[" + vr.getRecommendedVersion().toString() + ",)";
        } else {
            return vr.toString();
        }
    }

    @Override
    public String getCacheId() {
        if (StringUtils.isNotEmpty(version)) {
            // return the hashcodes of the parameter that matters
            return "" + version.hashCode();
        } else {
            return "0";
        }
    }

    /**
     * Gets the required version.
     *
     * @return the required version
     */
    public final String getVersion() {
        return this.version;
    }

    /**
     * Specify the required version. Some examples are:
     * <ul>
     * <li><code>2.0.4</code> Version 2.0.4 and higher (different from Maven meaning)</li>
     * <li><code>[2.0,2.1)</code> Versions 2.0 (included) to 2.1 (not included)</li>
     * <li><code>[2.0,2.1]</code> Versions 2.0 to 2.1 (both included)</li>
     * <li><code>[2.0.5,)</code> Versions 2.0.5 and higher</li>
     * <li><code>(,2.0.5],[2.1.1,)</code> Versions up to 2.0.5 (included) and 2.1.1 or higher</li>
     * </ul>
     *
     * @param theVersion the required version to set
     */
    public void setVersion(String theVersion) {
        this.version = theVersion;
    }
}
