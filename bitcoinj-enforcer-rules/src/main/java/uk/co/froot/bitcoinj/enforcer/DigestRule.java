package uk.co.froot.bitcoinj.enforcer;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.enforcer.rule.api.EnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.enforcer.rule.api.EnforcerRuleHelper;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.util.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Formatter;
import java.util.List;

/**
 * <p>Enforcer rule to provide the following to build process:</p>
 * <ul>
 * <li>Checking of hashes against an expected list</li>
 * </ul>
 *
 * @since 0.0.1
 *         
 */
public class DigestRule implements EnforcerRule {

  private String[] urns = null;

  private boolean buildSnapshot = false;
  private MessageDigest messageDigest = null;

  public String getCacheId() {
    return "id"; // This is not cacheable
  }

  public boolean isCacheable() {
    return false;
  }

  public boolean isResultValid(EnforcerRule arg0) {
    return false;
  }

  @SuppressWarnings("deprecation")
  public void execute(EnforcerRuleHelper helper) throws EnforcerRuleException {

    Log log = helper.getLog();

    log.info("Applying DigestRule");

    try {

      // Initialise the MessageDigest for SHA1
      messageDigest = MessageDigest.getInstance("SHA-1");

      MavenProject mavenProject = (MavenProject) helper.evaluate("${project}");

      // Build the snapshot first
      if (buildSnapshot) {
        buildSnapshot(mavenProject, log);
      }

      // Check for missing URNs (OK if we're just making a snapshot)
      if (urns == null && !buildSnapshot) {
        throw new EnforcerRuleException("Failing because there are no URNs in the <configuration> section. See the README for help.");
      }

      // Must be OK to verify
      verifyDependencies(mavenProject, log, helper);

    } catch (IOException e) {
      throw new EnforcerRuleException("Unable to read file: " + e.getLocalizedMessage(), e);
    } catch (ExpressionEvaluationException e) {
      throw new EnforcerRuleException("Unable to lookup an expression: " + e.getLocalizedMessage(), e);
    } catch (NoSuchAlgorithmException e) {
      throw new EnforcerRuleException("Unable to initialise MessageDigest: " + e.getLocalizedMessage(), e);
    }
  }

  /**
   * @param mavenProject The Maven project
   * @param log          The Maven log
   * @param helper       The Maven helper
   *
   * @throws EnforcerRuleException If something goes wrong
   */
  private void verifyDependencies(MavenProject mavenProject, Log log, EnforcerRuleHelper helper) throws EnforcerRuleException {

    log.info("Verifying dependencies");

    boolean failed = false;

    try {

      // get the various expressions out of the helper.

      ArtifactRepository localRepository;

      // Due to backwards compatibility issues the deprecated interface must be used here
      ArtifactFactory artifactFactory;
      ArtifactResolver resolver;
      localRepository = (ArtifactRepository) helper.evaluate("${localRepository}");

      artifactFactory = (ArtifactFactory) helper.getComponent(ArtifactFactory.class);

      resolver = (ArtifactResolver) helper.getComponent(ArtifactResolver.class);

      for (String urn : urns) {
        log.info("Verifying URN: " + urn);
        // Decode it into artifact co-ordinates
        String[] coordinates = urn.split(":");
        if (coordinates.length != 7) {
          throw new EnforcerRuleException("Failing because URN '" + urn + "' is not in format 'groupId:artifactId:version:type:classifier:scope:hash'");
        }

        String groupId = coordinates[0];
        String artifactId = coordinates[1];
        String version = coordinates[2];
        String type = coordinates[3];
        String classifier = "null".equalsIgnoreCase(coordinates[4]) ? null : coordinates[4];
        String scope = coordinates[5];
        String hash = coordinates[6];

        VersionRange versionRange = VersionRange.createFromVersion(version);
        Artifact artifact = artifactFactory.createDependencyArtifact(groupId, artifactId, versionRange, type, classifier, scope);

        resolver.resolve(artifact, mavenProject.getRemoteArtifactRepositories(), localRepository);

        String actual = digest(artifact.getFile());
        if (!actual.equals(hash)) {
          log.error("*** CRITICAL FAILURE *** Artifact does not match. Possible dependency-chain attack. Expected='" + hash + "' Actual='" + actual + "'");
          failed = true;
        }
      }

      if (failed) {
        throw new EnforcerRuleException("At least one artifact has not met expectations.");
      }

      // Translate the multitude of exceptions
    } catch (ExpressionEvaluationException e) {
      throw new EnforcerRuleException("Unable to lookup an expression: " + e.getLocalizedMessage(), e);
    } catch (ComponentLookupException e) {
      throw new EnforcerRuleException("Failing because a component lookup failed: " + e.getLocalizedMessage(), e);
    } catch (ArtifactResolutionException e) {
      throw new EnforcerRuleException("Failing due to artifact resolution: " + e.getLocalizedMessage(), e);
    } catch (ArtifactNotFoundException e) {
      throw new EnforcerRuleException("Failing due to artifact not found: " + e.getLocalizedMessage(), e);
    }

  }

  /**
   * @param project The project
   * @param log     The project log
   *
   * @throws IOException           If something goes wrong
   * @throws EnforcerRuleException If something goes wrong
   */
  private void buildSnapshot(MavenProject project, Log log) throws IOException, EnforcerRuleException {

    log.info("Building snapshot whitelist of all current artifacts");

    List<String> whitelist = new ArrayList<String>();

    for (Artifact artifact : project.getArtifacts()) {

      String artifactUrn = String.format("%s:%s:%s:%s:%s:%s",
        artifact.getGroupId(),
        artifact.getArtifactId(),
        artifact.getVersion(),
        artifact.getType(),
        artifact.getClassifier(),
        artifact.getScope()
      );

      log.debug("Examining artifact URN: " + artifactUrn);

      // Read in the signature file (if it exists)
      File sha1File = new File(artifact.getFile().getAbsoluteFile() + ".sha1");

      // Generate expectations
      String sha1Expected = null;
      if (sha1File.exists()) {
        // SHA1 is 40 characters in hex
        sha1Expected = FileUtils.fileRead(sha1File).substring(0, 40);
        log.debug("Found SHA1:" + sha1Expected);
      }

      // Generate actual
      String sha1Actual = digest(artifact.getFile());

      // Check expectations against actual
      // SHA1
      if (sha1Expected != null) {
        if (!sha1Expected.equals(sha1Actual)) {
          log.error("Artifact " + artifactUrn + " FAILED SHA1 verification. Expected='" + sha1Expected + "' Actual='" + sha1Actual + "'");
        } else {
          log.debug("Artifact " + artifactUrn + " PASSED SHA1 verification.");
          whitelist.add(artifactUrn + ":" + sha1Actual);
        }
      } else {
        log.warn("Artifact " + artifactUrn + " UNVERIFIED SHA1 (missing in repo).");
      }

    }

    // Provide the list in a natural order
    Collections.sort(whitelist);

    log.info("List of verified artifacts. If you are confident in the integrity of your repository you can use the list below:");
    log.info("<urns>");
    for (String urn : whitelist) {
      log.info("  <urn>" + urn + "</urn>");
    }
    log.info("</urns>");
  }

  /**
   * @param file The file to digest
   *
   * @return The hex version of the digest
   *
   * @throws EnforcerRuleException If something goes wrong
   */
  private String digest(File file) throws EnforcerRuleException {
    try {
      // Reset the digest to avoid initialisation delays
      messageDigest.reset();

      // Wrap the digest around the file input stream
      FileInputStream fis = new FileInputStream(file);
      DigestInputStream dis = new DigestInputStream(fis, messageDigest);

      // Read fully to get digest
      while (dis.read() != -1) {
      }

      // Clean up resources
      dis.close();
      fis.close();
      byte[] digest = messageDigest.digest();

      return byteToHex(digest);

    } catch (FileNotFoundException e) {
      throw new EnforcerRuleException("Unable to digest " + e.getLocalizedMessage(), e);
    } catch (IOException e) {
      throw new EnforcerRuleException("Unable to digest " + e.getLocalizedMessage(), e);
    }
  }

  /**
   * @param digest The digest of the message
   *
   * @return The hex version
   */
  private static String byteToHex(final byte[] digest) {
    Formatter formatter = new Formatter();
    for (byte b : digest) {
      formatter.format("%02x", b);
    }
    String result = formatter.toString();
    formatter.close();
    return result;
  }

  /**
   * @param buildSnapshot True if a snapshot of the current dependencies is required
   */
  public void setBuildSnapshot(boolean buildSnapshot) {
    this.buildSnapshot = buildSnapshot;
  }
}