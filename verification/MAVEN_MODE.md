# Maven repository mode

`maven.properties` is the source of truth for repository mode and the local
repository path. The template starts with:

```properties
maven.local=true
maven.localRepository=
```

With `maven.local=true`, Gradle tries Maven Local first for project dependencies,
buildscript dependencies, and plugin resolution, then uses the configured remote
repositories. This applies to ordinary builds and tests, including subprojects.
With `maven.local=false`, resolution uses remote repositories without consulting
Maven Local. This switch does not enable Gradle offline mode.

An empty `maven.localRepository` selects `<user.home>/.m2/repository`. Set an
absolute path to use a separate local repository. The same path is supplied to
Gradle's Maven resolver and `publishToMavenLocal`; shell variables are not expanded
inside a properties file. Use forward slashes in Windows paths, for example
`C:/Users/name/.m2/repository`.

```bash
./gradlew build
./gradlew publishToMavenLocal
```

Neither command needs `-Pproject.repositories.mavenLocal` or `-Dmaven.repo.local`.
The file takes precedence over those old command-line settings. Publication tasks
retain their explicit destinations: `publishToMavenLocal` writes locally, and
`publishToCentralPortal` uses the existing Central Portal flow. The mode changes
which dependencies those tasks build against. Publication verification stays enabled.
