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

import com.google.common.base.Preconditions;
import com.google.devrel.gmscore.tools.apk.arsc.ArscBlamer.ResourceEntry;
import com.google.inject.Inject;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Calculates extra information about an {@link ArscBlamer.ResourceEntry}, such as the total
 * APK size the entry is responsible for.
 *
 * This class is not thread-safe.
 */
public class ResourceEntryStatsCollector {

  /** The size in bytes of an offset in a chunk. */
  private static final int OFFSET_SIZE = 4;

  /** The size in bytes of overhead for styles, if present, in {@link StringPoolChunk}. */
  private static final int STYLE_OVERHEAD = 8;

  /**
   * The number of bytes, in addition to the header, that the {@link PackageChunk} has in overhead
   * excluding the chunks it contains.
   */
  private static final int PACKAGE_CHUNK_OVERHEAD = 8;

  private final Map<ResourceEntry, ResourceStatistics> stats = new HashMap<>();

  private final ArscBlamer blamer;

  private final ResourceTableChunk resourceTable;

  /**
   * Creates a new {@link ResourceEntryStatsCollector}.
   *
   * @param blamer The blamer that maps resource entries to what they use.
   * @param resourceTable The resource table that {@code blamer} is blamed on.
   */
  @Inject
  public ResourceEntryStatsCollector(ArscBlamer blamer, ResourceTableChunk resourceTable) {
    this.resourceTable = resourceTable;
    this.blamer = blamer;
  }

  public void compute() throws IOException {
    Preconditions.checkState(stats.isEmpty(), "Must only call #compute once.");
    blamer.blame();
    computeStringPoolSizes();
    computePackageSizes();
  }

  /** Returns entries for which there are computed stats. Must first call {@link #compute}. */
  public Map<ResourceEntry, ResourceStatistics> getStats() {
    Preconditions.checkState(!stats.isEmpty(), "Must call #compute() first.");
    return Collections.unmodifiableMap(stats);
  }

  /** Returns computed stats for a given entry. Must first call {@link #compute}. */
  public ResourceStatistics getStats(ResourceEntry entry) {
    Preconditions.checkState(!stats.isEmpty(), "Must call #compute() first.");
    return stats.containsKey(entry) ? stats.get(entry) : ResourceStatistics.EMPTY;
  }

  private void computeStringPoolSizes() throws IOException {
    computePoolSizes(resourceTable.getStringPool(), blamer.getStringToBlamedResources());
  }

  private void computePackageSizes() throws IOException {
    computeTypePoolSizes();
    computeKeyPoolSizes();
    computeTypeSpecSizes();
    computeTypeChunkSizes();
    computePackageChunkSizes();
  }

  private void computeTypePoolSizes() throws IOException {
    for (Entry<PackageChunk, List<ResourceEntry>[]> entry
        : blamer.getTypeToBlamedResources().entrySet()) {
      computePoolSizes(entry.getKey().getTypeStringPool(), entry.getValue());
    }
  }

  private void computeKeyPoolSizes() throws IOException {
    for (Entry<PackageChunk, List<ResourceEntry>[]> entry
        : blamer.getKeyToBlamedResources().entrySet()) {
      computePoolSizes(entry.getKey().getKeyStringPool(), entry.getValue());
    }
  }

  private void computeTypeSpecSizes() {
    for (Entry<PackageChunk, List<ResourceEntry>[]> entry
        : blamer.getTypeToBlamedResources().entrySet()) {
      computeTypeSpecSizes(entry.getKey(), entry.getValue());
    }
  }

  private void computeTypeChunkSizes() {
    for (Entry<TypeChunk.Entry, Collection<ResourceEntry>> entry
        : blamer.getTypeEntryToBlamedResources().asMap().entrySet()) {
      TypeChunk.Entry chunkEntry = entry.getKey();
      TypeChunk typeChunk = chunkEntry.parent();
      int size = chunkEntry.size() + OFFSET_SIZE;
      int count = typeChunk.getEntries().size();
      int nullEntries = typeChunk.getTotalEntryCount() - typeChunk.getEntries().size();
      int overhead = typeChunk.getHeaderSize() + nullEntries * OFFSET_SIZE;
      addSizes(entry.getValue(), overhead, size, count);
    }
  }

  private void computePackageChunkSizes() {
    for (Entry<PackageChunk, Collection<ResourceEntry>> entry
        : blamer.getPackageToBlamedResources().asMap().entrySet()) {
      int overhead = entry.getKey().getHeaderSize() + PACKAGE_CHUNK_OVERHEAD;
      addSizes(entry.getValue(), overhead, 0, 1);
    }
  }

  private void computePoolSizes(StringPoolChunk stringPool,
      List<ResourceEntry>[] usages) throws IOException {
    int overhead = stringPool.getHeaderSize();
    if (stringPool.getStyleCount() > 0) {
      overhead += STYLE_OVERHEAD;
    }

    // We have to iterate over the indices of the string pool, because it is possible that there are
    // indices which have *no* associated resource entry (i.e. references from XML files without an
    // entry in R).
    int count = 0;
    for (int i = 0; i < usages.length; ++i) {
      if (usages[i].isEmpty()) {
        overhead += computeStringAndStyleSize(stringPool, i);
      } else {
        ++count;
      }
    }

    // Now that we know the number of actual entries, we can compute the size.
    for (int i = 0; i < usages.length; ++i) {
      if (usages[i].isEmpty()) {
        continue;
      }
      int size = computeStringAndStyleSize(stringPool, i);
      addSizes(usages[i], overhead, size, count);
    }
  }

  private void computeTypeSpecSizes(PackageChunk packageChunk,
      List<ResourceEntry>[] usages) {
    for (int i = 0; i < usages.length; ++i) {
      // The 1 here is to convert back to a 1-based index.
      TypeSpecChunk typeSpec = packageChunk.getTypeSpecChunk(i + 1);
      // TypeSpecChunk entries share everything equally.
      addSizes(usages[i], typeSpec.getOriginalChunkSize(), 0, 1);
    }
  }

  /**
   * Given an {@code index} into a {@code stringPool}, return string's total size in bytes plus its
   * style, if any.
   *
   * @param stringPool The string pool containing the {@code index}.
   * @param index The (0-based) index of the string and (optional) style.
   * @throws IOException Thrown if the style's length could not be computed.
   */
  private int computeStringAndStyleSize(StringPoolChunk stringPool, int index)
      throws IOException {
    return computeStringSize(stringPool, index) + computeStyleSize(stringPool, index);
  }

  /** Given an {@code index} into a {@code stringPool}, return string's total size in bytes. */
  private int computeStringSize(StringPoolChunk stringPool, int index) {
    String string = stringPool.getString(index);
    int result = ResourceString.encodeString(string, stringPool.getStringType()).length;
    result += OFFSET_SIZE;
    return result;
  }

  /**
   * Given an {@code index} into a {@code stringPool}, return style's total size in bytes or 0 if
   * there's no style at that index.
   *
   * @throws IOException Thrown if the style's length could not be computed.
   */
  private int computeStyleSize(StringPoolChunk stringPool, int index) throws IOException {
    if (index >= stringPool.getStyleCount()) {  // No style at index
      return 0;
    }
    return stringPool.getStyle(index).toByteArray().length + OFFSET_SIZE;
  }

  /**
   * Adds to the {@link #stats} of {@code entries} that reference a value in a chunk the bytes it's
   * responsible for. This should only be called once per chunk-value pair.
   *
   * @param entries The resource entries referencing a single value in a chunk.
   * @param overhead The number of bytes of overhead of a chunk. Typically the header size.
   * @param size The size in bytes of a value in a chunk that {@code entries} reference.
   * @param count The total number of values in the chunk.
   */
  private void addSizes(Collection<ResourceEntry> entries, int overhead, int size, int count) {
    int usageCount = entries.size();
    for (ResourceEntry resourceEntry : entries) {
      // TODO(acornwall): Replace with Java 8's #getOrDefault when possible.
      if (!stats.containsKey(resourceEntry)) {
        stats.put(resourceEntry, new ResourceStatistics());
      }
      ResourceStatistics resourceStats = stats.get(resourceEntry);
      if (usageCount == 1) {
        resourceStats.addPrivateSize(size);
      } else {
        resourceStats.addSharedSize(size);
      }
      // Special case: If the chunk only has one relevant value, removing this entry will remove the
      // entire chunk.
      if (usageCount == 1 && count == 1) {
        resourceStats.addPrivateSize(overhead);
      }
      resourceStats.addProportionalSize(size, usageCount);
      resourceStats.addProportionalSize(overhead, usageCount * count);
    }
  }

  /** Stats for an individual {@link ArscBlamer.ResourceEntry}. */
  public static class ResourceStatistics {

    /** The empty, immutable instance of ResourceStatistics which contains 0 for all values. */
    public static final ResourceStatistics EMPTY = new ResourceStatistics();

    private int privateSize = 0;
    private int sharedSize = 0;
    private double proportionalSize = 0;

    private ResourceStatistics() {}

    /** The number of bytes that would be freed if this resource was removed. */
    public int getPrivateSize() {
      return privateSize;
    }

    /** The number of bytes taken up by this resource that are also shared with other resources. */
    public int getSharedSize() {
      return sharedSize;
    }

    /** The total size this resource is responsible for. */
    public double getProportionalSize() {
      return proportionalSize;
    }

    private void addPrivateSize(int privateSize) {
      this.privateSize += privateSize;
    }

    private void addSharedSize(int sharedSize) {
      this.sharedSize += sharedSize;
    }

    private void addProportionalSize(int numerator, int denominator) {
      this.proportionalSize += 1.0 * numerator / denominator;
    }
  }
}
