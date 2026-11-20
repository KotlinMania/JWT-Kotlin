package io.github.kotlinmania.build;

import groovy.json.JsonSlurper;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven;
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository;
import org.gradle.api.tasks.TaskCollection;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.plugins.signing.Sign;
import org.gradle.plugins.signing.SigningExtension;

/**
 * Configures Maven Central Portal staging, POM metadata, PGP signing, and bundle upload.
 */
public final class CentralPortalSupport {
    private CentralPortalSupport() {}

    public static void configure(Project project, String publishProjectName, boolean jvmFfiEnabled, boolean hasAppleTargets) {
        Project rootProject = project.getRootProject();
        PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);

        TaskProvider<Jar> emptyJavadocJar = project.getTasks().register("emptyJavadocJar", Jar.class, task -> {
            task.setDescription("Empty javadoc jar required by Maven Central for every publication.");
            task.getArchiveClassifier().set("javadoc");
        });

        publishing.getPublications().withType(MavenPublication.class).configureEach(pub -> {
            String rawArtifactId = pub.getArtifactId();
            if ("kotlinMultiplatform".equals(pub.getName()) || project.getName().equals(rawArtifactId)) {
                pub.setArtifactId(publishProjectName);
            } else if (rawArtifactId.startsWith(project.getName() + "-")) {
                pub.setArtifactId(publishProjectName + "-" + rawArtifactId.substring((project.getName() + "-").length()));
            }
            pub.artifact(emptyJavadocJar);
            pub.pom(pom -> {
                pom.getName().set(publishProjectName);
                pom.getDescription().set(prop(project, "project.pom.description", ""));
                pom.getInceptionYear().set("2026");
                pom.getUrl().set("https://github.com/KotlinMania/" + publishProjectName);
                // Comma-separated lists declare a multi-license (for example dual MIT / Apache-2.0) POM.
                String[] licenseNames = prop(project, "project.pom.licenseName", "MIT").split("\\s*,\\s*");
                String[] licenseUrls =
                    prop(project, "project.pom.licenseUrl", "https://opensource.org/licenses/MIT").split("\\s*,\\s*");
                if (licenseNames.length != licenseUrls.length) {
                    throw new GradleException(
                        "project.pom.licenseName and project.pom.licenseUrl must list the same number of entries");
                }
                pom.licenses(licenses -> {
                    for (int i = 0; i < licenseNames.length; i++) {
                        String licenseName = licenseNames[i];
                        String licenseUrl = licenseUrls[i];
                        licenses.license(license -> {
                            license.getName().set(licenseName);
                            license.getUrl().set(licenseUrl);
                            license.getDistribution().set("repo");
                        });
                    }
                });
                pom.developers(developers -> developers.developer(dev -> {
                    dev.getId().set("sydneyrenee");
                    dev.getName().set("Sydney Renee");
                    dev.getEmail().set("sydney@solace.ofharmony.ai");
                    dev.getUrl().set("https://github.com/sydneyrenee");
                    dev.getOrganization().set("KotlinMania");
                    dev.getOrganizationUrl().set("https://github.com/KotlinMania");
                }));
                pom.scm(scm -> {
                    scm.getUrl().set("https://github.com/KotlinMania/" + publishProjectName);
                    scm.getConnection().set("scm:git:git://github.com/KotlinMania/" + publishProjectName + ".git");
                    scm.getDeveloperConnection().set("scm:git:ssh://github.com/KotlinMania/" + publishProjectName + ".git");
                });
            });
        });

        publishing.repositories(repos -> repos.maven(maven -> {
            maven.setName("centralPortalStaging");
            maven.setUrl(project.uri(project.getLayout().getBuildDirectory().dir("staging-deploy")));
        }));

        SigningExtension signing = project.getExtensions().getByType(SigningExtension.class);
        String signingKey = propOrNull(project, "signingInMemoryKey");
        String signingKeyId = propOrNull(project, "signingInMemoryKeyId");
        String signingPassword = propOrNull(project, "signingInMemoryKeyPassword");
        boolean signingEnabled = !"false".equals(String.valueOf(project.findProperty("RELEASE_SIGNING_ENABLED"))) && signingKey != null;
        if (signingEnabled) {
            signing.useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword);
            signing.sign(publishing.getPublications());
        }

        TaskCollection<PublishToMavenRepository> centralPortalPublishTasks =
            project.getTasks().withType(PublishToMavenRepository.class).matching(t ->
                t.getName().endsWith("ToCentralPortalStagingRepository")
            );
        project.getTasks().withType(AbstractPublishToMaven.class).configureEach(t ->
            t.dependsOn(project.getTasks().withType(Sign.class))
        );

        TaskProvider<Zip> centralPortalBundle = project.getTasks().register("centralPortalBundle", Zip.class, task -> {
            task.setGroup("publishing");
            task.setDescription("Bundles the staged Maven artifacts into a Central Portal deployment zip.");
            task.dependsOn(centralPortalPublishTasks);
            task.from(project.getLayout().getBuildDirectory().dir("staging-deploy"));
            task.getArchiveFileName().set(publishProjectName + "-" + project.getVersion() + "-bundle.zip");
            task.getDestinationDirectory().set(project.getLayout().getBuildDirectory().dir("central-portal"));
        });

        TaskProvider<Task> publishToCentralPortal = project.getTasks().register("publishToCentralPortal", task -> {
            task.setGroup("publishing");
            task.setDescription("Uploads the deployment bundle to the Sonatype Central Portal.");
            task.dependsOn(centralPortalBundle);
            if (jvmFfiEnabled) {
                task.dependsOn("verifyJniDistribution");
            }
            task.doLast(t -> uploadToCentralPortal(project, publishProjectName, centralPortalBundle.get().getArchiveFile().get().getAsFile()));
        });

        project.getTasks().register("publishAndReleaseToMavenCentral", task -> {
            task.setGroup("publishing");
            task.setDescription("Alias for publishToCentralPortal to maintain compatibility with legacy publish workflows.");
            task.dependsOn(publishToCentralPortal);
        });

        project.getTasks().register("androidPublicationBundle", Zip.class, task -> {
            task.setGroup("verification");
            String externalRoot = propOrNull(project, "project.publication.android.rootStagingDirectory");
            String groupPath = project.getGroup().toString().replace('.', '/');
            String coordinatePath = groupPath + "/" + publishProjectName;
            String version = project.getVersion().toString();

            task.dependsOn(project.getTasks().matching(t ->
                t.getName().equals("publishAndroidPublicationToCentralPortalStagingRepository")
            ));
            task.from(project.getLayout().getBuildDirectory().dir("staging-deploy"), spec ->
                spec.include(coordinatePath + "-android/" + version + "/**")
            );

            if (externalRoot == null) {
                if (hasAppleTargets && !System.getProperty("os.name", "").startsWith("Mac")) {
                    task.doFirst(t -> {
                        throw new GradleException(
                            "Stage the real KMP root on macOS, then set project.publication.android.rootStagingDirectory and expectedRootHead"
                        );
                    });
                } else {
                    task.dependsOn(project.getTasks().matching(t ->
                        t.getName().equals("publishKotlinMultiplatformPublicationToCentralPortalStagingRepository")
                    ));
                    task.from(project.getLayout().getBuildDirectory().dir("staging-deploy"), spec ->
                        spec.include(coordinatePath + "/" + version + "/**")
                    );
                }
            } else {
                File rootDirectory = rootProject.file(externalRoot);
                File sourceReceipt = rootProject.file(externalRoot + ".source-head");
                String expectedHead = propOrNull(project, "project.publication.android.expectedRootHead");
                task.getInputs().file(sourceReceipt);
                if (expectedHead != null) {
                    task.getInputs().property("expectedRootHead", expectedHead);
                }
                task.from(rootDirectory, spec -> spec.include(coordinatePath + "/" + version + "/**"));
                task.doFirst(t -> {
                    if (expectedHead == null || !expectedHead.matches("[0-9a-f]{40}")) {
                        throw new GradleException("Set expectedRootHead to the exact source commit");
                    }
                    try {
                        if (!rootDirectory.isDirectory() || !sourceReceipt.isFile()
                            || !Files.readString(sourceReceipt.toPath(), StandardCharsets.UTF_8).trim().equals(expectedHead)) {
                            throw new GradleException("Missing or stale same-head KMP root staging receipt: " + sourceReceipt);
                        }
                    } catch (IOException e) {
                        throw new GradleException("Failed to read source receipt " + sourceReceipt, e);
                    }
                    File moduleFile = rootProject.file(
                        externalRoot + "/" + coordinatePath + "/" + version + "/" + publishProjectName + "-" + version + ".module"
                    );
                    if (!moduleFile.isFile()) {
                        throw new GradleException("Missing real KMP root module metadata in " + rootDirectory);
                    }
                });
            }
            task.setDuplicatesStrategy(DuplicatesStrategy.FAIL);
            task.getArchiveFileName().set("android-publication.zip");
            task.getDestinationDirectory().set(project.getLayout().getBuildDirectory().dir("verification"));
        });
    }

    private static void uploadToCentralPortal(Project project, String publishProjectName, File bundle) {
        String user = propOrNull(project, "mavenCentralUsername");
        if (user == null) {
            throw new GradleException("mavenCentralUsername is required to publish to the Central Portal.");
        }
        String password = propOrNull(project, "mavenCentralPassword");
        if (password == null) {
            throw new GradleException("mavenCentralPassword is required to publish to the Central Portal.");
        }
        String publishingType = prop(project, "centralPublishingType", "USER_MANAGED");
        String token = Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        if (!bundle.exists()) {
            throw new GradleException("Deployment bundle not found: " + bundle);
        }

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        if (isPublishedOnMavenCentral(client, project, publishProjectName)) {
            project.getLogger().lifecycle(
                "Maven Central already has " + project.getGroup() + ":" + publishProjectName + ":"
                    + project.getVersion() + " (HTTP 200); skipping duplicate upload."
            );
            return;
        }

        String boundary = "CentralPortalBoundary" + UUID.randomUUID().toString().replace("-", "");
        String crlf = "\r\n";
        byte[] preamble = (
            "--" + boundary + crlf +
            "Content-Disposition: form-data; name=\"bundle\"; filename=\"" + bundle.getName() + "\"" + crlf +
            "Content-Type: application/octet-stream" + crlf + crlf
        ).getBytes(StandardCharsets.UTF_8);
        byte[] epilogue = (crlf + "--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8);
        byte[] bundleBytes;
        try {
            bundleBytes = Files.readAllBytes(bundle.toPath());
        } catch (IOException e) {
            throw new GradleException("Failed to read bundle " + bundle, e);
        }
        byte[] body = new byte[preamble.length + bundleBytes.length + epilogue.length];
        System.arraycopy(preamble, 0, body, 0, preamble.length);
        System.arraycopy(bundleBytes, 0, body, preamble.length, bundleBytes.length);
        System.arraycopy(epilogue, 0, body, preamble.length + bundleBytes.length, epilogue.length);

        String deploymentName = publishProjectName + "-" + project.getVersion();
        URI uploadUri = URI.create(
            "https://central.sonatype.com/api/v1/publisher/upload?name=" + deploymentName + "&publishingType=" + publishingType
        );
        HttpRequest request = HttpRequest.newBuilder()
            .uri(uploadUri)
            .timeout(Duration.ofMinutes(10))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GradleException("Central Portal upload request interrupted", e);
        } catch (IOException e) {
            throw new GradleException("Central Portal upload request failed", e);
        }
        int uploadStatus = response.statusCode();
        if (uploadStatus < 200 || uploadStatus > 299) {
            if ((uploadStatus == 400 || uploadStatus == 409)
                && isPublishedOnMavenCentral(client, project, publishProjectName)) {
                project.getLogger().lifecycle(
                    "Central Portal upload returned HTTP " + uploadStatus
                        + ", and Maven Central already serves " + project.getGroup() + ":"
                        + publishProjectName + ":" + project.getVersion() + " (HTTP 200)."
                );
                return;
            }
            throw new GradleException("Central Portal upload failed: HTTP " + uploadStatus + " — " + response.body());
        }
        String deploymentId = response.body().trim();
        project.getLogger().lifecycle(
            "Central Portal upload accepted (deployment id: " + deploymentId + "). publishingType=" + publishingType + "."
        );

        boolean automatic = "AUTOMATIC".equalsIgnoreCase(publishingType);
        Set<String> terminalStates = automatic ? Set.of("PUBLISHED") : Set.of("VALIDATED", "PUBLISHED");
        URI statusUri = URI.create("https://central.sonatype.com/api/v1/publisher/status?id=" + deploymentId);
        int statusAttempts = Integer.parseInt(prop(project, "centralPublishStatusAttempts", "360"));
        long statusDelayMillis = Long.parseLong(prop(project, "centralPublishStatusDelayMillis", "10000"));
        int maxConsecutiveTransientErrors =
            Integer.parseInt(prop(project, "centralPublishMaxConsecutiveErrors", "12"));
        int consecutiveTransientErrors = 0;

        for (int attempt = 0; attempt < statusAttempts; attempt++) {
            HttpRequest statusReq = HttpRequest.newBuilder()
                .uri(statusUri)
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
            HttpResponse<String> statusResp;
            try {
                statusResp = client.send(statusReq, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GradleException("Central Portal status check interrupted", e);
            } catch (IOException e) {
                consecutiveTransientErrors++;
                project.getLogger().warn(
                    "Central Portal status check hit transient I/O error (" + e.getMessage() + ") on attempt "
                        + (attempt + 1) + "/" + statusAttempts + " (consecutive errors: "
                        + consecutiveTransientErrors + "/" + maxConsecutiveTransientErrors + ")."
                );
                if (consecutiveTransientErrors >= maxConsecutiveTransientErrors || attempt + 1 >= statusAttempts) {
                    throw new GradleException("Central Portal status check failed", e);
                }
                sleepStatusDelay(statusDelayMillis);
                continue;
            }
            int statusCode = statusResp.statusCode();
            if (statusCode == 429 || (statusCode >= 500 && statusCode <= 599)) {
                consecutiveTransientErrors++;
                project.getLogger().warn(
                    "Central Portal status check returned transient HTTP " + statusCode + " on attempt "
                        + (attempt + 1) + "/" + statusAttempts + " (consecutive errors: "
                        + consecutiveTransientErrors + "/" + maxConsecutiveTransientErrors + "): "
                        + truncateBody(statusResp.body())
                );
                if (consecutiveTransientErrors >= maxConsecutiveTransientErrors || attempt + 1 >= statusAttempts) {
                    throw new GradleException(
                        "Central Portal status check failed: HTTP " + statusCode + " — " + statusResp.body()
                    );
                }
                sleepStatusDelay(statusDelayMillis);
                continue;
            }
            if (statusCode < 200 || statusCode > 299) {
                throw new GradleException("Central Portal status check failed: HTTP " + statusCode + " — " + statusResp.body());
            }
            consecutiveTransientErrors = 0;
            Map<?, ?> statusBody = (Map<?, ?>) new JsonSlurper().parseText(statusResp.body());
            Object stateObj = statusBody.get("deploymentState");
            if (stateObj == null) {
                throw new GradleException("Central Portal status response did not contain deploymentState: " + statusResp.body());
            }
            String state = stateObj.toString();
            if ("FAILED".equals(state)) {
                Object errors = statusBody.get("errors");
                throw new GradleException("Central Portal deployment failed: " + (errors != null ? errors : statusResp.body()));
            }
            if (terminalStates.contains(state)) {
                project.getLogger().lifecycle("Central Portal deployment " + deploymentId + " reached " + state + ".");
                return;
            }
            project.getLogger().lifecycle(
                "Central Portal deployment " + deploymentId + " is " + state + " (" + (attempt + 1) + "/" + statusAttempts + ")."
            );
            if (attempt + 1 < statusAttempts) {
                sleepStatusDelay(statusDelayMillis);
            }
        }
        if (automatic && isPublishedOnMavenCentral(client, project, publishProjectName)) {
            project.getLogger().lifecycle(
                "Central Portal deployment " + deploymentId
                    + " timed out polling, but Maven Central already serves the published POM (HTTP 200)."
            );
            return;
        }
        throw new GradleException(
            "Central Portal deployment " + deploymentId + " did not reach " + terminalStates + " after " + statusAttempts + " checks."
        );
    }

    private static boolean isPublishedOnMavenCentral(HttpClient client, Project project, String publishProjectName) {
        String groupPath = project.getGroup().toString().replace('.', '/');
        String version = project.getVersion().toString();
        URI pomUri = URI.create(
            "https://repo1.maven.org/maven2/" + groupPath + "/" + publishProjectName + "/"
                + version + "/" + publishProjectName + "-" + version + ".pom"
        );
        HttpRequest headReq = HttpRequest.newBuilder()
            .uri(pomUri)
            .timeout(Duration.ofSeconds(60))
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build();
        try {
            HttpResponse<Void> headResp = client.send(headReq, HttpResponse.BodyHandlers.discarding());
            return headResp.statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GradleException("Interrupted while checking Maven Central for " + pomUri, e);
        } catch (IOException e) {
            project.getLogger().warn(
                "Maven Central HEAD check failed for " + pomUri + " (" + e.getMessage() + "); proceeding with upload."
            );
            return false;
        }
    }

    private static void sleepStatusDelay(long statusDelayMillis) {
        try {
            Thread.sleep(statusDelayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GradleException("Interrupted while waiting for Central Portal status", e);
        }
    }

    private static String truncateBody(String body) {
        if (body == null) {
            return "";
        }
        String singleLine = body.replace('\r', ' ').replace('\n', ' ').trim();
        return singleLine.length() <= 256 ? singleLine : singleLine.substring(0, 256) + "...";
    }

    private static String prop(Project project, String name, String defaultValue) {
        Object val = project.findProperty(name);
        return val != null ? val.toString().trim() : defaultValue;
    }

    private static String propOrNull(Project project, String name) {
        Object val = project.findProperty(name);
        if (val == null) {
            return null;
        }
        String trimmed = val.toString().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
