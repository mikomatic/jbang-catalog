/// usr/bin/env jbang "$0" "$@" ; exit $?
// JAVA 17+

// DEPS info.picocli:picocli:4.6.3
// DEPS com.github.spullara.mustache.java:compiler:0.9.10
// DEPS org.springframework.boot:spring-boot-configuration-processor:2.7.12

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import org.springframework.boot.configurationprocessor.metadata.ConfigurationMetadata;
import org.springframework.boot.configurationprocessor.metadata.ItemDeprecation;
import org.springframework.boot.configurationprocessor.metadata.ItemMetadata;
import org.springframework.boot.configurationprocessor.metadata.JsonMarshaller;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

/** Script to document application properties based on Spring Boot configuration metadata file */
@Command(
    name = "springPropertyDocumenter",
    mixinStandardHelpOptions = true,
    version = "springPropertyDocumenter 0.1",
    description = "Document spring boot properties based on property metadata")
class springPropertyDocumenter implements Callable<Integer> {

  public static final String DEFAULT_MD_TEMPLATE =
      """
      # Application configuration properties

      This document describes your custom configuration properties.
      Each property can be specified inside `application.yml`, env variable or as command line switches.

      Other configuration related to Spring Boot can be found in the [official documentation](https://docs.spring.io/spring-boot/docs/current/reference/html/application-properties.html#appendix.application-properties), see also Spring Boot's [relaxed binding](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.external-config.typesafe-configuration-properties.relaxed-binding).

      ## Configuration groups:

      {{#groups}}
      - [`{{name}}`](#{{name}}) : {{description}}
      {{/groups}}

      {{#properties}}

      ## `{{key}}`

      Properties for group: `{{key}}`

      | Name | Description | Default value | Deprecated |
      | ---- | ---- |---- |---- |
      {{#value}}
      | `{{name}}` | {{description}} | `{{defaultValue}}` | {{#deprecation}}true{{/deprecation}} |
      {{/value}}

      {{/properties}}
      """;

  @CommandLine.Option(
      names = {"--metadata-location-folders", "-m"},
      required = true,
      description =
          "Folder(s) containing spring boot configuration metadata files (defaults to current folder)",
      defaultValue = "./")
  private Path[] metadataLocationFolders;

  @CommandLine.Option(
      names = {"--output", "-o"},
      required = true,
      description = "Markdown file output filename")
  private Path output;

  public static void main(String... args) {
    int exitCode = new CommandLine(new springPropertyDocumenter()).execute(args);
    System.exit(exitCode);
  }

  /**
   * Find all spring boot configuration metadata files based on "configurationFiles" input.
   *
   * <p>Expects maven convention where
   *
   * @return the found metadata files
   */
  private List<Path> getMetadataLocationFolders() {
    final var files = new ArrayList<Path>();
    Arrays.stream(metadataLocationFolders)
        .forEach(
            path -> {
              try (Stream<Path> fileStream = Files.walk(path)) {
                Path pathEnding = Path.of("META-INF", "spring-configuration-metadata.json");
                fileStream.filter(file -> file.endsWith(pathEnding)).forEach(files::add);
              } catch (IOException e) {
                throw new RuntimeException(
                    "Error reading spring-configuration-metadata.json files", e);
              }
            });

    return files;
  }

  @Override
  public Integer call() throws Exception {
    List<Path> configurationFileList = getMetadataLocationFolders();
    if (configurationFileList.isEmpty()) {
      System.out.println("No configuration metadata files(s) found. Bye bye.");
      return 0;
    } else {
      System.out.println("Found file(s): %s.".formatted(configurationFileList));
    }

    List<ConfigurationMetadata> configurationMetadataList =
        configurationFileList.stream().map(springPropertyDocumenter::parseConfiguration).toList();

    List<ItemMetadata> topLevelGroups =
        configurationMetadataList.stream()
            .flatMap(configurationMetadata -> configurationMetadata.getItems().stream())
            .filter(itemMetadata -> itemMetadata.isOfItemType(ItemMetadata.ItemType.GROUP))
            .filter(itemMetadata -> !itemMetadata.getName().contains("."))
            .toList();

    Map<String, List<PrintableItemMetadata>> propertiesByTopLevelGroupName =
        configurationMetadataList.stream()
            .flatMap(configurationMetadata -> configurationMetadata.getItems().stream())
            .filter(itemMetadata -> itemMetadata.isOfItemType(ItemMetadata.ItemType.PROPERTY))
            .collect(
                groupingBy(
                    itemMetadata -> itemMetadata.getName().split("\\.")[0],
                    LinkedHashMap::new,
                    mapping(this::toPrintableItemMetadata, toList())));

    MustacheFactory mf = new DefaultMustacheFactory();
    Mustache mustache = mf.compile(new StringReader(DEFAULT_MD_TEMPLATE), "template");

    try (StringWriter stringWriter = new StringWriter()) {
      System.out.println("Generating documentation file : %s ...".formatted(output));
      var templateData =
          Map.of("groups", topLevelGroups, "properties", propertiesByTopLevelGroupName.entrySet());

      mustache.execute(stringWriter, templateData).flush();
      Files.writeString(output, stringWriter.toString());
      System.out.println("Generated documentation file.");
    }

    return 0;
  }

  private PrintableItemMetadata toPrintableItemMetadata(ItemMetadata itemMetadata) {
    Object defaultValueAsObject = itemMetadata.getDefaultValue();
    String defaultValue;
    if (defaultValueAsObject instanceof Object[] collection) {
      defaultValue = Arrays.stream(collection).map(String::valueOf).collect(joining(",", "", ""));
    } else {
      defaultValue = Optional.ofNullable(defaultValueAsObject).orElse("").toString();
    }
    return new PrintableItemMetadata(
        itemMetadata.getName(),
        itemMetadata.getDescription(),
        defaultValue,
        itemMetadata.getDeprecation());
  }

  private static ConfigurationMetadata parseConfiguration(Path configurationMetadataFile) {
    try {
      return new JsonMarshaller().read(new FileInputStream(configurationMetadataFile.toFile()));
    } catch (Exception e) {
      throw new RuntimeException("Error parsing spring property metadata json file", e);
    }
  }

  record PrintableItemMetadata(
      String name, String description, String defaultValue, ItemDeprecation deprecation) {}
}
