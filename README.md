# Incrementals

See [JEP-305](https://github.com/jenkinsci/jep/blob/master/jep/305/README.adoc) for context.

## Usage in plugin POMs

Since most Jenkins repositories host plugins, this use case will be documented first.
You must be using parent POM version [3.10](https://github.com/jenkinsci/plugin-pom/blob/master/CHANGELOG.md#310) or later.

### Enabling incrementals (the easy way)

Just run

```bash
mvn io.jenkins.tools.incrementals:incrementals-maven-plugin:incrementalify -DgenerateBackupPoms=false
```

or if your POM is already new enough (3.10+)

```bash
mvn incrementals:incrementalify
```

Check the usual build

```bash
mvn clean package
```

and if all is well,

```bash
git add .mvn pom.xml
git checkout -b incrementals
git commit -m Incrementalified.
```

and file as a pull request.

### Enabling incrementals (the hard way)

#### Enabling consumption of incrementals

If your plugin has (or may have) dependencies on incremental versions, run:

```bash
mkdir -p .mvn
echo -Pconsume-incrementals >> .mvn/maven.config
git add .mvn
```

(See [this guide](https://maven.apache.org/docs/3.3.1/release-notes.html#JVM_and_Command_Line_Options) for details on the `.mvn` directory.)

This profile merely activates access to the [Incrementals repository](https://repo.jenkins-ci.org/incrementals/).

#### Enabling production of incrementals

To produce incremental artifacts _from_ your plugin, first edit your `pom.xml`.
If your plugin declares

```xml
<version>1.23-SNAPSHOT</version>
```

then replace that with

```xml
<version>${revision}${changelist}</version>
```

and then in the `<properties>` section add

```xml
<revision>1.23</revision>
<changelist>-SNAPSHOT</changelist>
```

If you have a multimodule reactor build, the new `properties` need be defined only in the root POM,
but every child POM should use the edited `version` to refer to its `parent`.
(It should _not_ override the `version`.)
Intermodule `dependency`es may use `${project.version}` to refer to the `version` of the sibling.

Also change

```xml
<scm>
  <!-- … -->
  <tag>HEAD</tag>
</scm>
```

to


```xml
<scm>
  <!-- … -->
  <tag>${scmTag}</tag>
</scm>
```

Now run

```bash
mkdir -p .mvn
echo -Pmight-produce-incrementals >> .mvn/maven.config
```

Finally, configure `git-changelist-maven-extension` in `.mvn/extensions.xml`:

```xml
<extensions xmlns="http://maven.apache.org/EXTENSIONS/1.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/EXTENSIONS/1.0.0 http://maven.apache.org/xsd/core-extensions-1.0.0.xsd">
  <extension>
    <groupId>io.jenkins.tools.incrementals</groupId>
    <artifactId>git-changelist-maven-extension</artifactId>
    <version>1.0-beta-2</version>
  </extension>
</extensions>
```

You may now

```bash
git add .mvn pom.xml
```

and commit and push your edits.

#### Production _and_ consumption

A single plugin may both consume Incrementals releases, and produce its own.
Just make both kinds of edits.
(`.mvn/maven.config` may have multiple lines.)

### Producing incrementals

Assumes you have set up the `might-produce-incrementals` as above, either by hand or using the `incrementalify` goal.

If you file a pull request built on ci.jenkins.io,
and the pull request is up to date with its target branch,
and the build is stable,
the artifact will be automatically deployed to the Incrementals repository.
Your plugin will need to have a `github` field in
[`repository-permissions-updater`](https://github.com/jenkins-infra/repository-permissions-updater)
if it does not already.

To produce equivalent artifacts in your local repository while working offline:

```bash
mvn -Dset.changelist -DskipTests clean install
```

If you do not select the `-Dset.changelist` option, you will create a regular `*-SNAPSHOT` artifact.
(And that is what you _must_ do if you have any local modifications or untracked files.)

### Updating dependencies

Once you have some dependencies on incremental versions in your POM, you can

```bash
mvn incrementals:update
```

to get a newer version of some dependencies, if merged; or

```bash
mvn incrementals:update -Dbranch=yourghacct:experiments-JENKINS-12345
```

to get the most recent versions from some set of unmerged PRs.
Then commit and push the resulting `pom.xml` edits.

You will need GitHub credentials for this: [instructions](http://github-api.kohsuke.org/)

### Running Maven releases

You may still use the Maven release plugin (MRP) when `might-produce-incrementals` is activated:

```bash
mvn -B release:{prepare,perform}
```

The released artifacts should have sensible metadata.
(You may notice that they deploy a “flattened” POM file, but this should not break anything.)
However, after performing a traditional release, to resume being able to produce incrementals you must run:

```bash
mvn incrementals:reincrementalify
```

and commit and push the resulting `pom.xml` edits.

to avoid calling `mvn incrementals:reincrementalify` you can add `completionGoals` to your `properties` in `pom.xml`

```xml
  <properties>
    <completionGoals>incrementals:reincrementalify</completionGoals>
  </properties>
```

## Usage in other POMs

From repositories with POMs not inheriting from `org.jenkins-ci.plugins:plugin` you can follow similar steps to use Incrementals.
If you inherit from `org.jenkins-ci:jenkins`, the same profiles are available;
otherwise you will need to copy the definitions of the `consume-incrementals`, `might-produce-incrementals`, and `produce-incrementals` profiles from `org.jenkins-ci:jenkins`,
as well as the `incrementals.url` and `scmTag` properties,
into your parent POM or directly into your repository POM.
Some adjustment of `maven-enforcer-plugin` configuration may also be necessary.

## Offline testing

If you wish to test usage offline, run

```bash
docker run --rm --name nexus -p 8081:8081 -v nexus-data:/nexus-data sonatype/nexus3
```

add to your `~/.m2/settings.xml`:

```xml
<servers>
  <server>
    <id>incrementals</id>
    <username>admin</username>
    <password>admin123</password>
  </server>
</servers>
```

and then add to command lines consuming or producing incremental versions:

```
-Dincrementals.url=http://localhost:8081/repository/maven-releases/
```

or define an equivalent profile in local settings.
