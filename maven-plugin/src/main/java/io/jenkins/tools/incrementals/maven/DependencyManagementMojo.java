package io.jenkins.tools.incrementals.maven;

import org.apache.maven.model.Dependency;

/**
 * @author Oleg Nenashev
 * @since TODO
 */
public interface DependencyManagementMojo {

    default String toString(Dependency d) {
        StringBuilder buf = new StringBuilder();
        buf.append(d.getGroupId());
        buf.append(':');
        buf.append(d.getArtifactId());
        if (d.getType() != null && !d.getType().isEmpty()) {
            buf.append(':');
            buf.append(d.getType());
        } else {
            buf.append(":jar");
        }

        if (d.getClassifier() != null && !d.getClassifier().isEmpty()) {
            buf.append(':');
            buf.append(d.getClassifier());
        }

        if (d.getVersion() != null && !d.getVersion().isEmpty()) {
            buf.append(":");
            buf.append(d.getVersion());
        }

        return buf.toString();
    }
}
