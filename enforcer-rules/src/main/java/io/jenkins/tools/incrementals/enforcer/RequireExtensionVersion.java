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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.enforcer.rule.api.EnforcerRuleHelper;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.enforcer.AbstractVersionEnforcer;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;

/**
 * Verifies that {@code git-changelist-maven-extension}, if present, is sufficiently new.
 */
public class RequireExtensionVersion extends AbstractVersionEnforcer {

    private static final Pattern ID_PATTERN = Pattern.compile("\\QcoreExtension>io.jenkins.tools.incrementals:git-changelist-maven-extension:\\E(.+)");

    @Override
    public void execute(EnforcerRuleHelper erh) throws EnforcerRuleException {
        Log log = erh.getLog();
        List<AbstractMavenLifecycleParticipant> participants;
        try {
            participants = erh.getContainer().lookupList(AbstractMavenLifecycleParticipant.class);
        } catch (ComponentLookupException x) {
            log.warn(x);
            return;
        }
        for (AbstractMavenLifecycleParticipant participant : participants) {
            // TODO is there some better way of identifying a class loaded by Maven?
            ClassLoader loader = participant.getClass().getClassLoader();
            if (loader instanceof ClassRealm) {
                Matcher m = ID_PATTERN.matcher(((ClassRealm) loader).getId());
                if (m.matches()) {
                    enforceVersion(log, "git-changelist-maven-extension", getVersion(), new DefaultArtifactVersion(m.group(1)));
                }
            }
        }
    }

}
