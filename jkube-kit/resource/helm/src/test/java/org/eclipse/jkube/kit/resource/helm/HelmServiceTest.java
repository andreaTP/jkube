/**
 * Copyright (c) 2019 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at:
 *
 *     https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.jkube.kit.resource.helm;

import java.io.File;
import java.io.IOException;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import org.eclipse.jkube.kit.common.JKubeConfiguration;
import org.eclipse.jkube.kit.common.JavaProject;
import org.eclipse.jkube.kit.common.KitLogger;
import org.eclipse.jkube.kit.common.Maintainer;
import org.eclipse.jkube.kit.common.RegistryConfig;
import org.eclipse.jkube.kit.common.RegistryServerConfiguration;
import org.eclipse.jkube.kit.common.ResourceFileType;
import org.eclipse.jkube.kit.common.util.ResourceUtil;
import org.eclipse.jkube.kit.common.util.Serialization;
import org.eclipse.jkube.kit.config.resource.ResourceServiceConfig;
import org.eclipse.jkube.kit.resource.helm.HelmConfig.HelmType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HelmServiceTest {

  private HelmConfig.HelmConfigBuilder helmConfig;
  private JKubeConfiguration jKubeConfiguration;
  private ResourceServiceConfig resourceServiceConfig;
  private HelmService helmService;

  @BeforeEach
  void setUp() {
    helmConfig = HelmConfig.builder();
    jKubeConfiguration = JKubeConfiguration.builder()
      .project(JavaProject.builder().properties(new Properties()).build())
      .registryConfig(RegistryConfig.builder().settings(new ArrayList<>()).build()).build();
    resourceServiceConfig = new ResourceServiceConfig();
    helmService = new HelmService(jKubeConfiguration, resourceServiceConfig, new KitLogger.SilentLogger());
  }

  @AfterEach
  void tearDown() {
    helmService = null;
    helmConfig = null;
  }

  @Test
  void prepareSourceDirValid_withNonExistentDirectory_shouldThrowException() {
    File file = mock(File.class);
    // Given
    when(file.isDirectory()).thenReturn(true);
    // When
    IOException result = assertThrows(IOException.class,
            () -> HelmService.prepareSourceDir(helmConfig.build(), HelmType.OPENSHIFT));
    // Then
    assertThat(result).isNotNull()
            .hasMessageStartingWith("Chart source directory ")
            .hasMessageEndingWith("you need run 'mvn kubernetes:resource' before.");
  }

  @Test
  void createChartYaml() throws Exception {
    try (MockedStatic<ResourceUtil> resourceUtilMockedStatic = mockStatic(ResourceUtil.class)) {
      File outputDir = Files.createTempDirectory("chart-output").toFile();
      // Given
      helmConfig.chart("Chart Name").version("1337");
      // When
      helmService.createChartYaml(helmConfig.build(), outputDir);
      // Then
      ArgumentCaptor<Chart> argumentCaptor = ArgumentCaptor.forClass(Chart.class);
      resourceUtilMockedStatic.verify(() -> ResourceUtil.save(notNull(), argumentCaptor.capture(), eq(ResourceFileType.yaml)));
      assertThat(argumentCaptor.getValue())
          .hasFieldOrPropertyWithValue("apiVersion", "v1")
          .hasFieldOrPropertyWithValue("name", "Chart Name")
          .hasFieldOrPropertyWithValue("version", "1337");
    }
  }

  @Test
  void createChartYaml_whenInvalidChartYamlFragmentProvided_thenThrowException(@TempDir File outputDir) throws Exception {
    // Given
    File fragmentsDir = new File(Objects.requireNonNull(getClass().getResource("/invalid-helm-fragments")).getFile());
    jKubeConfiguration = jKubeConfiguration.toBuilder()
      .project(JavaProject.builder().properties(new Properties()).build())
      .build();
    resourceServiceConfig = ResourceServiceConfig.builder()
      .resourceDirs(Collections.singletonList(fragmentsDir)).build();
    // When + Then
    assertThatIllegalArgumentException()
      .isThrownBy(() ->
        new HelmService(jKubeConfiguration, resourceServiceConfig, new KitLogger.SilentLogger()).createChartYaml(helmConfig.build(), outputDir))
      .withMessageContaining("Failure in parsing Helm Chart fragment: ");
  }

  @Test
  void createChartYaml_whenValidChartYamlFragmentProvided_thenMergeFragmentChart(@TempDir File outputDir) throws Exception {
    // Given
    File fragmentsDir = new File(Objects.requireNonNull(getClass().getResource("/valid-helm-fragments")).getFile());
    Properties properties = new Properties();
    properties.put("chart.name", "name-from-fragment");
    jKubeConfiguration = jKubeConfiguration.toBuilder()
      .project(JavaProject.builder().properties(properties).build())
      .build();
    resourceServiceConfig = ResourceServiceConfig.builder()
      .resourceDirs(Collections.singletonList(fragmentsDir)).build();
    helmConfig
      .chart("Chart Name")
      .version("1337")
      .description("Description from helmconfig")
      .home("https://example.com")
      .sources(Collections.singletonList("https://source.example.com"))
      .keywords(Collections.singletonList("ci"))
      .maintainers(Collections.singletonList(Maintainer.builder().name("maintainer-from-config").build()))
      .icon("test-icon")
      .engine("gotpl")
      .dependencies(Collections.singletonList(HelmDependency.builder().name("dependency-from-config").build()));
    // When
    new HelmService(jKubeConfiguration, resourceServiceConfig, new KitLogger.SilentLogger())
      .createChartYaml(helmConfig.build(), outputDir);
    // Then
    final Map<?, ?> savedChart = Serialization.unmarshal(new File(outputDir, "Chart.yaml"), Map.class);
    assertThat(savedChart)
      .hasFieldOrPropertyWithValue("apiVersion", "v1")
      .hasFieldOrPropertyWithValue("name", "name-from-fragment")
      .hasFieldOrPropertyWithValue("version", "version-from-fragment")
      .hasFieldOrPropertyWithValue("description", "Description from helmconfig")
      .hasFieldOrPropertyWithValue("home", "https://example.com")
      .hasFieldOrPropertyWithValue("icon", "test-icon")
      .hasFieldOrPropertyWithValue("engine", "gotpl")
      .hasFieldOrPropertyWithValue("keywords", Collections.singletonList("fragment"))
      .hasFieldOrPropertyWithValue("sources", Collections.singletonList("https://source.example.com"))
      .hasFieldOrPropertyWithValue("maintainers", Collections.singletonList(Collections.singletonMap("name", "maintainer-from-config")))
      .hasFieldOrPropertyWithValue("dependencies", Collections.singletonList(Collections.singletonMap("name", "dependency-from-config")));
  }

  @Test
  void createChartYamlWithDependencies() throws Exception {
    try (MockedStatic<ResourceUtil> resourceUtilMockedStatic = mockStatic(ResourceUtil.class)) {
      // Given
      File outputDir = Files.createTempDirectory("chart-outputdir").toFile();
      HelmDependency helmDependency = new HelmDependency()
          .toBuilder()
          .name("nginx")
          .version("1.2.3.")
          .repository("repository")
          .build();
      helmConfig.chart("Chart Name").version("1337")
          .dependencies(Collections.singletonList(helmDependency));
      // When
      helmService.createChartYaml(helmConfig.build(), outputDir);
      // Then
      ArgumentCaptor<Chart> argumentCaptor = ArgumentCaptor.forClass(Chart.class);
      resourceUtilMockedStatic.verify(() -> ResourceUtil.save(notNull(), argumentCaptor.capture(), eq(ResourceFileType.yaml)));
      assertThat(argumentCaptor.getValue())
          .hasFieldOrPropertyWithValue("apiVersion", "v1")
          .hasFieldOrPropertyWithValue("name", "Chart Name")
          .hasFieldOrPropertyWithValue("version", "1337")
          .hasFieldOrPropertyWithValue("dependencies",
              Collections.singletonList(helmDependency));
    }
  }

  @Test
  void uploadChart_withValidRepository_shouldUpload()
      throws IOException, BadUploadException {
    try (MockedConstruction<HelmUploader> helmUploaderMockedConstruction = mockConstruction(HelmUploader.class,
        (mock, ctx) -> doNothing().when(mock).uploadSingle(any(File.class), any()))) {
      // Given
      final HelmRepository helmRepository = completeValidRepository().name("stable-repo").build();
      helmConfig
          .types(Collections.singletonList(HelmType.KUBERNETES))
          .chart("chartName")
          .version("1337")
          .chartExtension("tar.gz")
          .outputDir("target")
          .tarballOutputDir("target")
          .snapshotRepository(HelmRepository.builder().name("Snapshot-Repo").build())
          .stableRepository(helmRepository);
      // When
      helmService.uploadHelmChart(helmConfig.build());
      // Then
      ArgumentCaptor<File> argumentCaptor = ArgumentCaptor.forClass(File.class);
      assertThat(helmUploaderMockedConstruction.constructed()).hasSize(1);
      HelmUploader constructedHelmUploader = helmUploaderMockedConstruction.constructed().get(0);
      verify(constructedHelmUploader).uploadSingle(argumentCaptor.capture(), eq(helmRepository));
      String fileName = "chartName-1337.tar.gz";
      assertThat(argumentCaptor.getValue())
          .hasName(fileName);
    }
  }

  @Test
  void uploadHelmChart_withInvalidRepositoryConfiguration_shouldFail() {
    // Given
    final HelmConfig helm = helmConfig.chart("chart").version("1337-SNAPSHOT")
        .snapshotRepository(HelmRepository.builder().name("INVALID").build())
        .build();
    // When
    final IllegalStateException result = assertThrows(IllegalStateException.class,
      () -> helmService.uploadHelmChart(helm));
    // Then
    assertThat(result).hasMessage("No repository or invalid repository configured for upload");
  }

  @Test
  void uploadHelmChart_withMissingRepositoryConfiguration_shouldFail() {
    // Given
    final HelmConfig helm = helmConfig.chart("chart").version("1337-SNAPSHOT").build();
    // When
    final IllegalStateException result = assertThrows(IllegalStateException.class,
      () -> helmService.uploadHelmChart(helm));
    // Then
    assertThat(result).hasMessage("No repository or invalid repository configured for upload");
  }

  @Test
  void uploadHelmChart_withServerConfigurationWithoutUsername_shouldFail() {
    // Given
    final HelmConfig helm = helmConfig.chart("chart").version("1337-SNAPSHOT")
        .snapshotRepository(completeValidRepository().username(null).build()).build();
    jKubeConfiguration.getRegistryConfig().getSettings()
        .add(RegistryServerConfiguration.builder().id("SNAP-REPO").build());
    // When
    final IllegalArgumentException result = assertThrows(IllegalArgumentException.class, () ->
        helmService.uploadHelmChart(helm));
    // Then
    assertThat(result).hasMessage("Repo SNAP-REPO was found in server list but has no username/password.");
  }

  @Test
  void uploadHelmChart_withServerConfigurationWithoutPassword_shouldFail() {
    // Given
    final HelmConfig helm = helmConfig.chart("chart").version("1337-SNAPSHOT")
        .snapshotRepository(completeValidRepository().password(null).build()).build();
    jKubeConfiguration.getRegistryConfig().getSettings()
        .add(RegistryServerConfiguration.builder().id("SNAP-REPO").build());
    // When
    final IllegalArgumentException result = assertThrows(IllegalArgumentException.class, () ->
        helmService.uploadHelmChart(helm));
    // Then
    assertThat(result).hasMessage("Repo SNAP-REPO has a username but no password defined.");
  }


  @Test
  void uploadHelmChart_withMissingServerConfiguration_shouldFail() {
    // Given
    final HelmConfig helm = helmConfig.chart("chart").version("1337-SNAPSHOT")
        .snapshotRepository(completeValidRepository().username(null).build()).build();
    jKubeConfiguration.getRegistryConfig().getSettings()
        .add(RegistryServerConfiguration.builder().id("DIFFERENT").build());
    // When
    final IllegalArgumentException result = assertThrows(IllegalArgumentException.class, () ->
        helmService.uploadHelmChart(helm));
    // Then
    assertThat(result).hasMessage("No credentials found for SNAP-REPO in configuration or settings.xml server list.");
  }

  private static HelmRepository.HelmRepositoryBuilder completeValidRepository() {
    return HelmRepository.builder()
        .name("SNAP-REPO")
        .type(HelmRepository.HelmRepoType.ARTIFACTORY)
        .url("https://example.com/artifactory")
        .username("User")
        .password("S3cret");
  }
}
