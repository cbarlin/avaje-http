package io.avaje.http.maven.openapi;

import io.avaje.http.openapi.utils.OpenAPIUtils;
import io.swagger.v3.oas.models.OpenAPI;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.artifact.ProjectArtifact;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@Mojo(
  name = "openapi",
  defaultPhase = LifecyclePhase.PROCESS_CLASSES,
  threadSafe = true,
  requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME
)
public class OpenApiMojo extends AbstractMojo {

  /**
   * A cache of dependencies --> extracted OpenAPI definitions (if present)
   * <p>
   * This is so that multi-module builds don't wind up re-scanning the same JARs over and over
   */
  private static final ConcurrentHashMap<String, Optional<OpenAPI>> DEPENDENCY_CACHE = new ConcurrentHashMap<>();

  /**
   * Relative path to find the generated openapi.json file.
   */
  @Parameter(name = "source")
  String source;

  /**
   * Relative path to put the openapi.json.
   */
  @Parameter(name = "destination")
  String destination;

  /**
   * The directory holding the class files we want to transform.
   */
  @Parameter(property = "project.build.outputDirectory")
  String buildOut;

  @Parameter(property = "project.build.resources[0].directory")
  String buildResourceDir;

  @Parameter(property = "project.basedir")
  String baseDir;

  /**
   * Skip the merging of OpenAPI definitions
   */
  @Parameter(name = "skipMergeOpenApi")
  Boolean skipMergeOpenApi;

  /**
   * Additional files to parse that contain OpenAPI definitions
   */
  @Parameter(name = "additionalOpenApiJsonFiles")
  String[] additionalOpenApiJsonFiles;

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Override
  public void execute() throws MojoExecutionException {
    processApiCopy();
    if (!Boolean.TRUE.equals(skipMergeOpenApi)) {
      mergeOpenApiDefinitions();
    }
  }

  private void processApiCopy() {
    if (source == null) {
      // the location that the APT javalin-generator writes to
      source = "meta/openapi.json";
    }
    if (destination == null) {
      // location in src/main/resources to move openapi doc to
      destination = "public/openapi.json";
    }

    File sourceFile = new File(new File(buildOut), source);
    if (!sourceFile.exists()) {
      getLog().warn("openapi file not found at  " + sourceFile);
      return;
    }

    File srcMainRes = new File(buildResourceDir);
    if (destination != null) {
      File destFile = new File(srcMainRes, destination);
      File destDir = destFile.getParentFile();
      if (!destDir.exists() && !destDir.mkdirs()) {
        getLog().error("Failed to make directory " + destDir);
      } else {
        try {
          moveToMainResources(sourceFile, destFile);
        } catch (IOException e) {
          getLog().error("Failed to copy openapi file", e);
        }
      }
    }
  }

  /**
   * Copy the file to src/main/resources ...
   */
  private void moveToMainResources(File sourceFile, File destFile) throws IOException {
    // copy into src/main/resources ...
    Files.copy(sourceFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
    getLog().info("copied openapi file to " + destFile);
    if (!sourceFile.delete()) {
      getLog().warn("Failed to delete the temporary openapi file " + sourceFile);
    } else {
      deleteParentIfEmpty(sourceFile);
    }
  }

  /**
   * Delete the parent meta directory if it is empty.
   */
  private void deleteParentIfEmpty(File sourceFile) {
    final File meta = sourceFile.getParentFile();
    final String[] list = meta.list();
    if (list != null && list.length == 0) {
      if (meta.delete()) {
        getLog().debug("deleted empty meta directory");
      }
    }
  }

  /**
   * Merge all known OpenAPI definitions together
   */
  private void mergeOpenApiDefinitions() throws MojoExecutionException {
    final Set<Artifact> artifacts = project.getArtifacts();
    if (artifacts == null || artifacts.isEmpty()) {
      return;
    }
    final ArrayList<OpenAPI> definitions = new ArrayList<>();
    for (final Artifact artifact : artifacts) {
      extractOpenApiDefinitionFromArtifact(artifact, definitions);
    }
    if (additionalOpenApiJsonFiles != null) {
      for (final String additional : additionalOpenApiJsonFiles) {
        extractOpenApiAdditional(new File(additional)).ifPresent(definitions::add);
      }
    }
    final File currentProjectFile = new File(new File(buildResourceDir), destination);
    if (currentProjectFile.exists()) {
      extractOpenApiDefinitionCurrentProject(currentProjectFile, definitions);
    }
    if (definitions.isEmpty() || (currentProjectFile.exists() && definitions.size() == 1)) {
      getLog().info("No need to merge openAPI definitions");
      return;
    }
    final OpenAPI merged = OpenAPIUtils.merge(definitions.toArray(OpenAPI[]::new));
    try {
      final File destDir = currentProjectFile.getParentFile();
      if (!destDir.exists() && !destDir.mkdirs()) {
        getLog().error("Failed to make directory " + destDir);
      } else {
        // This is the only way to output pretty print JSON
        final String output = OpenAPIUtils.toJson(merged);
        try (final FileWriter fw = new FileWriter(currentProjectFile)) {
          fw.write(output);
        }
        getLog().info("Successfully merged OpenAPI definitions");
      }
    } catch (Exception e) {
      throw new MojoExecutionException("Cannot write out merged OpenAPI definition", e);
    }
  }

  private void extractOpenApiDefinitionCurrentProject(final File currentProjectFile, final ArrayList<OpenAPI> definitions) throws MojoExecutionException {
    try (final InputStream is = new FileInputStream(currentProjectFile)) {
      definitions.add(0, OpenAPIUtils.fromJson(is));
    } catch (Exception e) {
      throw new MojoExecutionException("Error parsing the current projects OpenAPI implementation", e);
    }
  }

  private void extractOpenApiDefinitionFromArtifact(final Artifact artifact, final List<OpenAPI> definitions) throws MojoExecutionException {
    if (artifact instanceof ProjectArtifact) {
      final ProjectArtifact projectArtifact = (ProjectArtifact) artifact;
      extractOpenApiProject(projectArtifact).ifPresent(definitions::add);
    } else {
      final File file = artifact.getFile();
      if (file == null || !file.exists()) {
        return;
      }
      if (file.getName().toLowerCase().endsWith(".jar")) {
        extractOpenApiJar(file).ifPresent(definitions::add);
      }
    }
  }

  private Optional<OpenAPI> extractOpenApiAdditional(final File file) throws MojoExecutionException {
    getLog().info("Loading path: " + file.getAbsolutePath());
    try (final InputStream is = new FileInputStream(file)) {
      return Optional.ofNullable(OpenAPIUtils.fromJson(is));
    } catch (Exception e) {
      throw new MojoExecutionException("Unable to read OpenAPI definitions from additional file", e);
    }
  }

  private Optional<OpenAPI> extractOpenApiProject(final ProjectArtifact projectArtifact) throws MojoExecutionException {
    final String sourceDirectory = projectArtifact.getProject().getProperties().getProperty("project.build.resources[0].directory");
    final File otherProjectFile = new File(new File(sourceDirectory), destination);
    if (otherProjectFile.canRead()) {
      try (final InputStream is = new FileInputStream(otherProjectFile)) {
        return Optional.ofNullable(OpenAPIUtils.fromJson(is));
      } catch (Exception e) {
        throw new MojoExecutionException("Unable to read OpenAPI definitions from module currently depended on: " + projectArtifact.getProject().getName(), e);
      }
    }
    return Optional.empty();
  }

  /**
   * Obtain the OpenAPI definition from either the cache or the JAR file
   *
   * @param jarFile the JAR file being scanned
   * @return the OpenAPI definition, if it exists
   */
  private Optional<OpenAPI> extractOpenApiJar(final File jarFile) {
    return DEPENDENCY_CACHE.computeIfAbsent(jarFile.getAbsolutePath(), ignored -> extractOpenApiJarImpl(jarFile));
  }

  /**
   * Scan an already-known JAR file for an OpenAPI definition
   *
   * @param jarFile the JAR file being scanned
   * @return the OpenAPI definition, if it exists
   */
  private Optional<OpenAPI> extractOpenApiJarImpl(final File jarFile) {
    try(final JarFile jar = new JarFile(jarFile)) {
      final Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        final JarEntry jarEntry = entries.nextElement();
        if (jarEntry.getName().equals("openapi.json") || jarEntry.getName().endsWith("/openapi.json")) {
          // Excellent, this is what we needed!
          try(
            final InputStream inputStream = jar.getInputStream(jarEntry);
            final BufferedInputStream bis = new BufferedInputStream(inputStream);
          ) {
            return Optional.ofNullable(OpenAPIUtils.fromJson(bis));
          }
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(new MojoExecutionException("Unable to read the OpenAPI definition out of a dependency", e));
    }
    return Optional.empty();
  }
}
