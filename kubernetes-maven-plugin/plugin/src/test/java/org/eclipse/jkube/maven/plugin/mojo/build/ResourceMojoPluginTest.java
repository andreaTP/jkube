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
package org.eclipse.jkube.maven.plugin.mojo.build;

import org.eclipse.jkube.kit.common.ResourceFileType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.util.stream.Stream;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

class ResourceMojoPluginTest {

  private XPath xPath;
  private Document mavenPluginXml;

  @BeforeEach
  void setUp() throws Exception {
    final DocumentBuilderFactory domFactory = DocumentBuilderFactory.newInstance();
    domFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    xPath = XPathFactory.newInstance().newXPath();
    mavenPluginXml = domFactory.newDocumentBuilder()
      .parse(ResourceMojoTest.class.getResourceAsStream("/META-INF/maven/plugin.xml"));
  }

  @DisplayName("Resource mojo plugin entry has @Parameter configurations")
  @ParameterizedTest(name = "{index} => ResourceMojo has ''{0}'' parameter with property ''{1}'', ''{2}'' type, and ''{3}'' defaultValue")
  @MethodSource("parametersData")
  void parameters(String fieldName, String property, Class<?> type, String defaultValue) throws Exception {
    final Element parameter = (Element) xPath.evaluate(
      "/plugin/mojos/mojo/goal[text() = 'resource']/../parameters/parameter/name[text() = '" + fieldName + "']/..",
      mavenPluginXml, XPathConstants.NODE);
    final Element configuration = (Element) xPath.evaluate(
      "/plugin/mojos/mojo/goal[text() = 'resource']/../configuration/" + fieldName,
      mavenPluginXml, XPathConstants.NODE);
    assertThat(parameter)
      .returns(type.getName(), p -> p.getElementsByTagName("type").item(0).getTextContent());
    assertThat(configuration)
      .returns(type.getName(), c -> c.getAttribute("implementation"))
      .returns(defaultValue, c -> c.getAttribute("default-value"))
      .returns("${" + property + "}", Element::getTextContent);
  }

  static Stream<Arguments> parametersData() {
    return Stream.of(
      Arguments.of("workDir", "jkube.workDir", File.class, "${project.build.directory}/jkube"),
      Arguments.of("resourceDir", "jkube.resourceDir", File.class, "${basedir}/src/main/jkube"),
      Arguments.of("environment", "jkube.environment", String.class, ""),
      Arguments.of("targetDir", "jkube.targetDir", File.class, "${project.build.outputDirectory}/META-INF/jkube"),
      Arguments.of("resourceFileType", "jkube.resourceType", ResourceFileType.class, ""),
      Arguments.of("interpolateTemplateParameters", "jkube.interpolateTemplateParameters", Boolean.class, "true"),
      Arguments.of("skipResourceValidation", "jkube.skipResourceValidation", Boolean.class, "false"),
      Arguments.of("failOnValidationError", "jkube.failOnValidationError", Boolean.class, "false")
    );
  }
}
