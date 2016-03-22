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

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * Analyzes an APK to:
 *
 * <ul>
 * <li>Blame resource configurations on their entry count (entries keeping the config around).
 * <li>Blame strings in resources.arsc that have no base configuration.
 * <li>Blame resources on their different configurations.
 * </ul>
 */
public class ArscBlamer {

  /** Maps package key pool indices to blamed resources. */
  private final Map<PackageChunk, List<ResourceEntry>[]> keyToBlame = new HashMap<>();

  /** Maps types to blamed resources. */
  private final Map<PackageChunk, List<ResourceEntry>[]> typeToBlame = new HashMap<>();

  /** Maps package to blamed resources. */
  private final Multimap<PackageChunk, ResourceEntry> packageToBlame = HashMultimap.create();

  /** Maps string indices to blamed resources. */
  private final List<ResourceEntry>[] stringToBlame;

  /** Maps type chunk entries to blamed resources. */
  private final Multimap<TypeChunk.Entry, ResourceEntry> typeEntryToBlame = HashMultimap.create();

  /** Maps resources to the type chunk entries they reference. */
  private Multimap<ResourceEntry, TypeChunk.Entry> resourceEntries;

  /** Maps resources which have no base config to the type chunk entries they reference. */
  private Multimap<ResourceEntry, TypeChunk.Entry> baselessKeys;

  /** Contains all of the type chunks in {@link #resourceTable}. */
  private List<TypeChunk> typeChunks;

  /** This is the {@link ResourceTableChunk} inside of the resources.arsc file in the APK. */
  private final ResourceTableChunk resourceTable;

  /**
   * Creates a new {@link ArscBlamer}.
   *
   * @param resourceTable The resources.arsc resource table to blame.
   */
  @Inject
  public ArscBlamer(ResourceTableChunk resourceTable) {
    this.resourceTable = resourceTable;
    this.stringToBlame = createEntryListArray(resourceTable.getStringPool().getStringCount());
  }

  /** Generates blame mappings. */
  public void blame() {
    Multimap<ResourceEntry, TypeChunk.Entry> entries = getResourceEntries();
    for (Entry<ResourceEntry, Collection<TypeChunk.Entry>> entry : entries.asMap().entrySet()) {
      ResourceEntry resourceEntry = entry.getKey();
      PackageChunk packageChunk = Preconditions.checkNotNull(
          resourceTable.getPackage(resourceEntry.packageName()));
      int keyCount = packageChunk.getKeyStringPool().getStringCount();
      int typeCount = packageChunk.getTypeStringPool().getStringCount();
      for (TypeChunk.Entry chunkEntry : entry.getValue()) {
        blameKeyOrType(keyToBlame, packageChunk, chunkEntry.keyIndex(), resourceEntry, keyCount);
        blameKeyOrType(typeToBlame, packageChunk, chunkEntry.parent().getId() - 1, resourceEntry,
            typeCount);
        blameFromTypeChunkEntry(chunkEntry);
      }
      blamePackage(packageChunk, resourceEntry);
    }
    Multimaps.invertFrom(entries, typeEntryToBlame);
    for (TypeChunk.Entry entry : typeEntryToBlame.keySet()) {
      blameFromTypeChunkEntry(entry);
    }
  }

  private void blameKeyOrType(Map<PackageChunk, List<ResourceEntry>[]> keyOrType,
      PackageChunk packageChunk, int keyIndex, ResourceEntry entry, int entryCount) {
    if (!keyOrType.containsKey(packageChunk)) {
      keyOrType.put(packageChunk, createEntryListArray(entryCount));
    }
    keyOrType.get(packageChunk)[keyIndex].add(entry);
  }

  private void blamePackage(PackageChunk packageChunk, ResourceEntry entry) {
    packageToBlame.put(packageChunk, entry);
  }

  private void blameFromTypeChunkEntry(TypeChunk.Entry chunkEntry) {
    for (ResourceValue value : getAllResourceValues(chunkEntry)) {
      for (ResourceEntry entry : typeEntryToBlame.get(chunkEntry)) {
        switch (value.type()) {
          case STRING:
            blameString(value.data(), entry);
            break;
          default:
            break;
        }
      }
    }
  }

  /** Returns all {@link ResourceValue} for a single {@code entry}. */
  private Collection<ResourceValue> getAllResourceValues(TypeChunk.Entry entry) {
    Set<ResourceValue> values = new HashSet<ResourceValue>();
    ResourceValue resourceValue = entry.value();
    if (resourceValue != null) {
      values.add(resourceValue);
    }
    for (ResourceValue value : entry.values().values()) {
      values.add(value);
    }
    return values;
  }

  private void blameString(int stringIndex, ResourceEntry entry) {
    stringToBlame[stringIndex].add(entry);
  }

  /** Must first call {@link #blame}. */
  public Map<PackageChunk, List<ResourceEntry>[]> getKeyToBlamedResources() {
    return Collections.unmodifiableMap(keyToBlame);
  }

  /** Must first call {@link #blame}. */
  public Map<PackageChunk, List<ResourceEntry>[]> getTypeToBlamedResources() {
    return Collections.unmodifiableMap(typeToBlame);
  }

  /** Must first call {@link #blame}. */
  public Multimap<PackageChunk, ResourceEntry> getPackageToBlamedResources() {
    return Multimaps.unmodifiableMultimap(packageToBlame);
  }

  /** Must first call {@link #blame}. */
  public List<ResourceEntry>[] getStringToBlamedResources() {
    return stringToBlame;
  }

  /** Must first call {@link #blame}. */
  public Multimap<TypeChunk.Entry, ResourceEntry> getTypeEntryToBlamedResources() {
    return Multimaps.unmodifiableMultimap(typeEntryToBlame);
  }

  /** Returns a multimap of keys for which there is no default resource. */
  public Multimap<ResourceEntry, TypeChunk.Entry> getBaselessKeys() {
    if (baselessKeys != null) {
      return baselessKeys;
    }
    Multimap<ResourceEntry, TypeChunk.Entry> result = HashMultimap.create();
    for (Entry<ResourceEntry, Collection<TypeChunk.Entry>> entry
        : getResourceEntries().asMap().entrySet()) {
      Collection<TypeChunk.Entry> chunkEntries = entry.getValue();
      if (!hasBaseConfiguration(chunkEntries)) {
        result.putAll(entry.getKey(), chunkEntries);
      }
    }
    baselessKeys = result;
    return result;
  }

  /** Returns a multimap of resource entries to the chunk entries they reference in this APK. */
  public Multimap<ResourceEntry, TypeChunk.Entry> getResourceEntries() {
    if (resourceEntries != null) {
      return resourceEntries;
    }
    Multimap<ResourceEntry, TypeChunk.Entry> result = HashMultimap.create();
    for (TypeChunk typeChunk : getTypeChunks()) {
      for (TypeChunk.Entry entry : typeChunk.getEntries().values()) {
        result.put(ResourceEntry.create(entry), entry);
      }
    }
    resourceEntries = result;
    return result;
  }

  /** Returns all {@link TypeChunk} in resources.arsc. */
  public List<TypeChunk> getTypeChunks() {
    if (typeChunks != null) {
      return typeChunks;
    }
    List<TypeChunk> result = new ArrayList<>();
    for (PackageChunk packageChunk : resourceTable.getPackages()) {
      for (TypeChunk typeChunk : packageChunk.getTypeChunks()) {
        result.add(typeChunk);
      }
    }
    typeChunks = result;
    return result;
  }

  private boolean hasBaseConfiguration(Collection<TypeChunk.Entry> entries) {
    for (TypeChunk.Entry entry : entries) {
      if (entry.parent().getConfiguration().isDefault()) {
        return true;
      }
    }
    return false;
  }

  private static List<ResourceEntry>[] createEntryListArray(int size) {
    ArrayListResourceEntry[] result = new ArrayListResourceEntry[size];
    for (int i = 0; i < size; ++i) {
      result[i] = new ArrayListResourceEntry();
    }
    return result;
  }

  /** Allows creation of concrete parameterized type arr {@link ArscBlamer#createEntryListArray}. */
  private static class ArrayListResourceEntry extends ArrayList<ResourceEntry> {

    private ArrayListResourceEntry() {
      super(2);  // ~90-95% of these lists end up with only 1 or 2 elements.
    }
  }

  /** Describes a single resource entry. */
  @AutoValue
  public abstract static class ResourceEntry {
    public abstract String packageName();
    public abstract String typeName();
    public abstract String entryName();

    static ResourceEntry create(TypeChunk.Entry entry) {
      PackageChunk packageChunk = Preconditions.checkNotNull(entry.parent().getPackageChunk());
      String packageName = packageChunk.getPackageName();
      String typeName = entry.typeName();
      String entryName = entry.key();
      return new AutoValue_ArscBlamer_ResourceEntry(packageName, typeName, entryName);
    }
  }
}
