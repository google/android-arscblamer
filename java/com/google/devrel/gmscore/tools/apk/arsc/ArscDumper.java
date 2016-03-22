/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devrel.gmscore.tools.apk.arsc;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.Multimap;
import com.google.devrel.gmscore.tools.apk.arsc.ArscBlamer.ResourceEntry;
import com.google.devrel.gmscore.tools.apk.arsc.ResourceEntryStatsCollector.ResourceStatistics;
import com.google.devrel.gmscore.tools.common.InjectedApplication;
import com.google.devrel.gmscore.tools.common.flags.CommonParams;
import com.google.inject.Inject;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.opencsv.CSVWriter;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nullable;

/**
 * Pulls useful information from an APK's resources.arsc file. This can be used to see the different
 * resource configurations in the APK, their size, and the entries in those configurations.
 *
 * <p>This can also be used to get a list of the different entry names, or to get a list of resource
 * entries for which no default value exists (baseless keys).
 *
 * <p>Example usage to save all resource configurations to a CSV file:
 *
 * <pre>ArscDumper.jar --apk=/apk_dir/my.apk --output=/csv_dir/my.csv --type=configs</pre>
 *
 * <p>This CSV could then be sorted by "Null Entries" in descending order to spot resource
 * configurations that could potentially be removed for large byte savings.
 */
public class ArscDumper {

  /** The type of dumper ArscDumper should output. */
  private enum Type {
    CONFIGS, ENTRIES, BASELESS_KEYS
  }

  /** Columns for the CSV returned for resource configs. */
  private static final List<String> CONFIGS_COLUMNS = ImmutableList.<String>builder()
      .add("Type")
      .add("Config")
      .add("Size")
      .add("Null Entries")
      .add("Entry Count")
      .add("Density")
      .add("Keys")
      .addAll(getConfigurationHeaders())
      .build();

  /** Columns for the CSV returned for entries / baseless keys. */
  private static final List<String> ENTRIES_COLUMNS = ImmutableList.of(
      "Type",
      "Name",
      "Private Size",
      "Shared Size",
      "Proportional Size",
      "Config Count",
      "Configs");

  private final ArscBlamer blamer;

  private final ResourceEntryStatsCollector collector;

  public static void main(String[] args) throws IOException {
    InjectedApplication application = new InjectedApplication.Builder(args)
        .withParameter(Params.class, CommonParams.class)
        .withModule(new ArscModule())
        .build();
    ArscDumper dumper = application.get(ArscDumper.class);
    Params params = application.get(Params.class);
    CommonParams commonParams = application.get(CommonParams.class);

    try (BufferedWriter writer = new BufferedWriter(getWriter(commonParams.getOutput()))) {
      switch (params.type) {
        case CONFIGS:
          dumper.dumpResourceConfigs(writer, params.keys);
          break;
        case ENTRIES:
          dumper.dumpEntries(writer);
          break;
        case BASELESS_KEYS:
          dumper.dumpBaselessKeys(writer);
          break;
        default:
          throw new UnsupportedOperationException(
              String.format("Missing implementation for type: %s.", params.type));
      }
    }
  }

  /**
   * Creates a new {@link ArscDumper}.
   *
   * @param blamer The blamer to dump information from.
   * @param collector The collector to compute resource entry stats from.
   */
  @Inject
  public ArscDumper(ArscBlamer blamer, ResourceEntryStatsCollector collector) {
    this.blamer = blamer;
    this.collector = collector;
  }

  /**
   * Writes a CSV dump of the resource configurations in the APK.
   *
   * @param writer The writer that will be used to write the CSV.
   * @param showKeys True if {@link ResourceEntry} keys should be shown in the output.
   * @throws IOException Thrown if {@code writer} could not be written to.
   */
  public void dumpResourceConfigs(Writer writer, boolean showKeys) throws IOException {
    try (AutoCloseableCsvWriter csvWriter = new AutoCloseableCsvWriter(writer)) {
      csvWriter.writeNext(CONFIGS_COLUMNS);
      for (TypeChunk typeChunk : getTypeChunksBySparsity()) {
        csvWriter.writeNext(dumpResourceConfig(typeChunk, showKeys));
      }
    }
  }

  /**
   * Returns a CSV row (as a list of strings) describing a particular resource configuration. If
   * showKeys is true, the "Keys" column will be populated with the keys of the resource entries in
   * that configuration. Otherwise, the "Keys" column will be blank.
   *
   * @param chunk The chunk to dump the configuration from.
   * @param showKeys True if "Keys" should contain the entries in {@code chunk}.
   * @return A CSV row describing a particular resource configuration.
   */
  private List<String> dumpResourceConfig(TypeChunk chunk, boolean showKeys) {
    Map<Integer, TypeChunk.Entry> entries = chunk.getEntries();
    double density = 1.0 * entries.size() / chunk.getTotalEntryCount();
    int size = chunk.getOriginalChunkSize();
    List<String> keyNames = new ArrayList<>();
    if (showKeys) {
      for (TypeChunk.Entry entry : entries.values()) {
        keyNames.add(entry.key());
      }
    }
    String keys = Joiner.on(' ').join(keyNames);
    return ImmutableList.<String>builder()
        .add(chunk.getTypeName())
        .add(chunk.getConfiguration().toString())
        .add(String.valueOf(size))
        .add(String.valueOf(chunk.getTotalEntryCount() - entries.size()))
        .add(String.valueOf(entries.size()))
        .add(String.format("%.4f", density))
        .add(keys)
        .addAll(getConfigurationParts(chunk.getConfiguration()))
        .build();
  }

  /**
   * Returns a CSV dump of the resource entries in this APK.
   *
   * @param writer The writer that will be used to write the CSV.
   * @throws IOException Thrown if {@code writer} could not be written to.
   */
  public void dumpEntries(Writer writer) throws IOException {
    dumpEntries(writer, blamer.getResourceEntries());
  }

  /**
   * Returns a CSV dump of resource keys which have no default value ("any" configuration).
   *
   * @param writer The writer that will be used to write the CSV.
   * @throws IOException Thrown if {@code writer} could not be written to.
   */
  public void dumpBaselessKeys(Writer writer) throws IOException {
    dumpEntries(writer, blamer.getBaselessKeys());
  }

  private void dumpEntries(Writer writer, Multimap<ResourceEntry, TypeChunk.Entry> entries)
      throws IOException {
    collector.compute();
    try (AutoCloseableCsvWriter csvWriter = new AutoCloseableCsvWriter(writer)) {
      csvWriter.writeNext(ENTRIES_COLUMNS);
      for (Entry<ResourceEntry, Collection<TypeChunk.Entry>> entry : entries.asMap().entrySet()) {
        csvWriter.writeNext(dumpEntry(entry, collector.getStats(entry.getKey())));
      }
    }
  }

  private List<String> dumpEntry(
      Entry<ResourceEntry, ? extends Iterable<TypeChunk.Entry>> entry, ResourceStatistics stats) {
    ResourceEntry resourceEntry = entry.getKey();
    Set<String> configParts = new TreeSet<>();  // Prevents duplicates of the same configuration

    for (TypeChunk.Entry chunkEntry : entry.getValue()) {
      configParts.add(chunkEntry.parent().getConfiguration().toString());
    }

    return ImmutableList.<String>builder()
        .add(resourceEntry.typeName())
        .add(resourceEntry.entryName())
        .add(String.valueOf(stats.getPrivateSize()))
        .add(String.valueOf(stats.getSharedSize()))
        .add(new BigDecimal(stats.getProportionalSize())
            .setScale(10, RoundingMode.HALF_EVEN)
            .toString())
        .add(String.valueOf(configParts.size()))
        .add(Joiner.on(' ').join(configParts))
        .build();
  }

  /** Returns a list of {@link TypeChunk} ordered by number of resource entries it has. */
  private List<TypeChunk> getTypeChunksBySparsity() {
    List<TypeChunk> result = new ArrayList<>(blamer.getTypeChunks());
    Collections.sort(result, new Comparator<TypeChunk>() {
      @Override
      public int compare(TypeChunk o1, TypeChunk o2) {
        return Integer.valueOf(o1.getEntries().size()).compareTo(o2.getEntries().size());
      }
    });
    return result;
  }

  private static List<String> getConfigurationHeaders() {
    Builder<String> builder = ImmutableList.builder();
    for (ResourceConfiguration.Type type : ResourceConfiguration.Type.values()) {
      builder.add(type.toString());
    }
    return builder.build();
  }

  private static List<String> getConfigurationParts(ResourceConfiguration configuration) {
    Map<ResourceConfiguration.Type, String> parts = configuration.toStringParts();
    Builder<String> builder = ImmutableList.builder();
    for (ResourceConfiguration.Type key : ResourceConfiguration.Type.values()) {
      builder.add(parts.containsKey(key) ? parts.get(key) : "");
    }
    return builder.build();
  }

  private static Writer getWriter(@Nullable File output) throws IOException {
    return (output == null) ? new OutputStreamWriter(System.out) : new FileWriter(output);
  }

  /** A wrapper around {@link CSVWriter} to allow {@link AutoCloseable}. */
  private static class AutoCloseableCsvWriter implements AutoCloseable {

    private final CSVWriter csvWriter;

    public AutoCloseableCsvWriter(Writer writer) {
      csvWriter = new CSVWriter(writer);
    }

    public void writeNext(Collection<String> line) {
      csvWriter.writeNext(line.toArray(new String[line.size()]));
    }

    @Override
    public void close() throws IOException {
      csvWriter.close();
    }
  }

  /** Provides params specific to {@link ArscDumper}. */
  @Parameters(separators = " =")
  public static class Params {
    @Parameter(names = "--type",
        description = "The type of output to return. Values: [configs, entries, baseless_keys]")
    private Type type = Type.CONFIGS;

    @Parameter(names = "--keys",
        description = "If true, include all key names for the entries in configs")
    private Boolean keys = false;
  }
}
