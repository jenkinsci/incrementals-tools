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

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * List of plugins and comments for plugins.txt.
 * @author Oleg Nenashev
 * @since TODO
 */
public class PluginRefList extends ArrayList<PluginRef> {

    public static PluginRefList fromFile(File file) throws IOException {
        PluginRefList plugins = new PluginRefList();
        try(BufferedReader br = new BufferedReader(new FileReader(file))) {
            for(String line; (line = br.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) {
                    plugins.add(PluginRef.forComment(line));
                } else {
                    plugins.add(PluginRef.fromString(line));
                }
            }
        }

        return plugins;
    }

    @NonNull
    public List<Dependency> toDependencyList() {
        List<Dependency> depList = new ArrayList<>(this.size());
        for (PluginRef plugin : this) {
            depList.add(plugin.toDependency());
        }
        return depList;
    }


    public void writeToFile(@NonNull File dest) throws IOException {
        List<String> outputLines = new ArrayList<>(this.size());
        for (PluginRef ref : this) {
            outputLines.add(ref.toPluginsTxtString());
        }

        Files.writeString(dest.toPath(), String.join("\n", outputLines));
    }
}
