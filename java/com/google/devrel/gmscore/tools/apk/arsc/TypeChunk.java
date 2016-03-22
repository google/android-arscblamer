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
import com.google.common.io.LittleEndianDataOutputStream;
import com.google.common.primitives.UnsignedBytes;

import java.io.ByteArrayOutputStream;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nullable;

/**
 * Represents a type chunk, which contains the resource values for a specific resource type and
 * configuration in a {@link PackageChunk}. The resource values in this chunk correspond to
 * the array of type strings in the enclosing {@link PackageChunk}.
 *
 * <p>A {@link PackageChunk} can have multiple of these chunks for different
 * (configuration, resource type) combinations.
 */
public final class TypeChunk extends Chunk {

  /** The type identifier of the resource type this chunk is holding. */
  private final int id;

  /** The number of resources of this type at creation time. */
  private final int entryCount;

  /** The offset (from {@code offset}) in the original buffer where {@code entries} start. */
  private final int entriesStart;

  /** The resource configuration that these resource entries correspond to. */
  private ResourceConfiguration configuration;

  /** A sparse list of resource entries defined by this chunk. */
  private final Map<Integer, Entry> entries = new TreeMap<>();

  protected TypeChunk(ByteBuffer buffer, @Nullable Chunk parent) {
    super(buffer, parent);
    id = UnsignedBytes.toInt(buffer.get());
    buffer.position(buffer.position() + 3);  // Skip 3 bytes for packing
    entryCount = buffer.getInt();
    entriesStart = buffer.getInt();
    configuration = ResourceConfiguration.create(buffer);
  }

  @Override
  protected void init(ByteBuffer buffer) {
    int offset = this.offset + entriesStart;
    for (int i = 0; i < entryCount; ++i) {
      Entry entry = Entry.create(buffer, offset, this);
      if (entry != null) {
        entries.put(i, entry);
      }
    }
  }

  /** Returns the (1-based) type id of the resource types that this {@link TypeChunk} is holding. */
  public int getId() {
    return id;
  }

  /** Returns the name of the type this chunk represents (e.g. string, attr, id). */
  public String getTypeName() {
    PackageChunk packageChunk = getPackageChunk();
    Preconditions.checkNotNull(packageChunk, "%s has no parent package.", getClass());
    StringPoolChunk typePool = packageChunk.getTypeStringPool();
    Preconditions.checkNotNull(typePool, "%s's parent package has no type pool.", getClass());
    return typePool.getString(getId() - 1);  // - 1 here to convert to 0-based index
  }

  /** Returns the resource configuration that these resource entries correspond to. */
  public ResourceConfiguration getConfiguration() {
    return configuration;
  }

  /**
   * Sets the resource configuration that this chunk's entries correspond to.
   *
   * @param configuration The new configuration.
   */
  public void setConfiguration(ResourceConfiguration configuration) {
    this.configuration = configuration;
  }

  /** Returns the total number of entries for this type + configuration, including null entries. */
  public int getTotalEntryCount() {
    return entryCount;
  }

  /** Returns a sparse list of 0-based indices to resource entries defined by this chunk. */
  public Map<Integer, Entry> getEntries() {
    return Collections.unmodifiableMap(entries);
  }

  /** Returns true if this chunk contains an entry for {@code resourceId}. */
  public boolean containsResource(ResourceIdentifier resourceId) {
    PackageChunk packageChunk = Preconditions.checkNotNull(getPackageChunk());
    int packageId = packageChunk.getId();
    int typeId = getId();
    return resourceId.packageId() == packageId
        && resourceId.typeId() == typeId
        && entries.containsKey(resourceId.entryId());
  }

  /**
   * Overrides the entries in this chunk at the given index:entry pairs in {@code entries}.
   * For example, if the current list of entries is {0: foo, 1: bar, 2: baz}, and {@code entries}
   * is {1: qux, 3: quux}, then the entries will be changed to {0: foo, 1: qux, 2: baz}. If an entry
   * has an index that does not exist in the dense entry list, then it is considered a no-op for
   * that single entry.
   *
   * @param entries A sparse list containing index:entry pairs to override.
   */
  public void overrideEntries(Map<Integer, Entry> entries) {
    for (Map.Entry<Integer, Entry> entry : entries.entrySet()) {
      int index = entry.getKey() != null ? entry.getKey() : -1;
      overrideEntry(index, entry.getValue());
    }
  }

  /**
   * Overrides an entry at the given index. Passing null for the {@code entry} will remove that
   * entry from {@code entries}. Indices < 0 or >= {@link #getTotalEntryCount()} are a no-op.
   *
   * @param index The 0-based index for the entry to override.
   * @param entry The entry to override, or null if the entry should be removed at this location.
   */
  public void overrideEntry(int index, @Nullable Entry entry) {
    if (index >= 0 && index < entryCount) {
      if (entry != null) {
        entries.put(index, entry);
      } else {
        entries.remove(index);
      }
    }
  }

  protected String getString(int index) {
    ResourceTableChunk resourceTable = getResourceTableChunk();
    Preconditions.checkNotNull(resourceTable, "%s has no resource table.", getClass());
    return resourceTable.getStringPool().getString(index);
  }

  protected String getKeyName(int index) {
    PackageChunk packageChunk = getPackageChunk();
    Preconditions.checkNotNull(packageChunk, "%s has no parent package.", getClass());
    StringPoolChunk keyPool = packageChunk.getKeyStringPool();
    Preconditions.checkNotNull(keyPool, "%s's parent package has no key pool.", getClass());
    return keyPool.getString(index);
  }

  @Nullable
  private ResourceTableChunk getResourceTableChunk() {
    Chunk chunk = getParent();
    while (chunk != null && !(chunk instanceof ResourceTableChunk)) {
      chunk = chunk.getParent();
    }
    return chunk != null && chunk instanceof ResourceTableChunk ? (ResourceTableChunk) chunk : null;
  }

  /** Returns the package enclosing this chunk, if any. Else, returns null. */
  @Nullable
  public PackageChunk getPackageChunk() {
    Chunk chunk = getParent();
    while (chunk != null && !(chunk instanceof PackageChunk)) {
      chunk = chunk.getParent();
    }
    return chunk != null && chunk instanceof PackageChunk ? (PackageChunk) chunk : null;
  }

  @Override
  protected Type getType() {
    return Chunk.Type.TABLE_TYPE;
  }

  /** Returns the number of bytes needed for offsets based on {@code entries}. */
  private int getOffsetSize() {
    return entryCount * 4;
  }

  private int writeEntries(DataOutput payload, ByteBuffer offsets, boolean shrink)
      throws IOException {
    int entryOffset = 0;
    for (int i = 0; i < entryCount; ++i) {
      Entry entry = entries.get(i);
      if (entry == null) {
        offsets.putInt(Entry.NO_ENTRY);
      } else {
        byte[] encodedEntry = entry.toByteArray(shrink);
        payload.write(encodedEntry);
        offsets.putInt(entryOffset);
        entryOffset += encodedEntry.length;
      }
    }
    entryOffset = writePad(payload, entryOffset);
    return entryOffset;
  }

  @Override
  protected void writeHeader(ByteBuffer output) {
    int entriesStart = getHeaderSize() + getOffsetSize();
    output.putInt(id);  // Write an unsigned byte with 3 bytes padding
    output.putInt(entryCount);
    output.putInt(entriesStart);
    output.put(configuration.toByteArray(false));
  }

  @Override
  protected void writePayload(DataOutput output, ByteBuffer header, boolean shrink)
      throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ByteBuffer offsets = ByteBuffer.allocate(getOffsetSize()).order(ByteOrder.LITTLE_ENDIAN);
    try (LittleEndianDataOutputStream payload = new LittleEndianDataOutputStream(baos)) {
      writeEntries(payload, offsets, shrink);
    }
    output.write(offsets.array());
    output.write(baos.toByteArray());
  }

  /** An {@link Entry} in a {@link TypeChunk}. Contains one or more {@link ResourceValue}. */
  @AutoValue
  public abstract static class Entry implements SerializableResource {

    /** An entry offset that indicates that a given resource is not present. */
    public static final int NO_ENTRY = 0xFFFFFFFF;

    /** Set if this is a complex resource. Otherwise, it's a simple resource. */
    private static final int FLAG_COMPLEX = 0x0001;

    /** Size of a single resource id + value mapping entry. */
    private static final int MAPPING_SIZE = 4 + ResourceValue.SIZE;

    /** Number of bytes in the header of the {@link Entry}. */
    public abstract int headerSize();

    /** Resource entry flags. */
    public abstract int flags();

    /** Index into {@link PackageChunk#getKeyStringPool} identifying this entry. */
    public abstract int keyIndex();

    /** The value of this resource entry, if this is not a complex entry. Else, null. */
    @Nullable
    public abstract ResourceValue value();

    /** The extra values in this resource entry if this {@link #isComplex}. */
    public abstract Map<Integer, ResourceValue> values();

    /**
     * Entry into {@link PackageChunk} that is the parent {@link Entry} to this entry.
     * This value only makes sense when this is complex ({@link #isComplex} returns true).
     */
    public abstract int parentEntry();

    /** The {@link TypeChunk} that this resource entry belongs to. */
    public abstract TypeChunk parent();

    /** Returns the name of the type this chunk represents (e.g. string, attr, id). */
    public final String typeName() {
      return parent().getTypeName();
    }

    /** The total number of bytes that this {@link Entry} takes up. */
    public final int size() {
      return headerSize() + (isComplex() ? values().size() * MAPPING_SIZE : ResourceValue.SIZE);
    }

    /** Returns the key name identifying this resource entry. */
    public final String key() {
      return parent().getKeyName(keyIndex());
    }

    /** Returns true if this is a complex resource. */
    public final boolean isComplex() {
      return (flags() & FLAG_COMPLEX) != 0;
    }

    /**
     * Creates a new {@link Entry} whose contents start at the 0-based position in
     * {@code buffer} given by a 4-byte value read from {@code buffer} and then added to
     * {@code baseOffset}. If the value read from {@code buffer} is equal to {@link #NO_ENTRY}, then
     * null is returned as there is no resource at that position.
     *
     * <p>Otherwise, this position is parsed and returned as an {@link Entry}.
     *
     * @param buffer A buffer positioned at an offset to an {@link Entry}.
     * @param baseOffset Offset that must be added to the value at {@code buffer}'s position.
     * @param parent The {@link TypeChunk} that this resource entry belongs to.
     * @return New {@link Entry} or null if there is no resource at this location.
     */
    @Nullable
    public static Entry create(ByteBuffer buffer, int baseOffset, TypeChunk parent) {
      int offset = buffer.getInt();
      if (offset == NO_ENTRY) {
        return null;
      }
      int position = buffer.position();
      buffer.position(baseOffset + offset);  // Set buffer position to resource entry start
      Entry result = newInstance(buffer, parent);
      buffer.position(position);  // Restore buffer position
      return result;
    }

    @Nullable
    private static Entry newInstance(ByteBuffer buffer, TypeChunk parent) {
      int headerSize = buffer.getShort() & 0xFFFF;
      int flags = buffer.getShort() & 0xFFFF;
      int keyIndex = buffer.getInt();
      ResourceValue value = null;
      Map<Integer, ResourceValue> values = new LinkedHashMap<>();
      int parentEntry = 0;
      if ((flags & FLAG_COMPLEX) != 0) {
        parentEntry = buffer.getInt();
        int valueCount = buffer.getInt();
        for (int i = 0; i < valueCount; ++i) {
          values.put(buffer.getInt(), ResourceValue.create(buffer));
        }
      } else {
        value = ResourceValue.create(buffer);
      }
      return new AutoValue_TypeChunk_Entry(
          headerSize, flags, keyIndex, value, values, parentEntry, parent);
    }

    @Override
    public final byte[] toByteArray() {
      return toByteArray(false);
    }

    @Override
    public final byte[] toByteArray(boolean shrink) {
      ByteBuffer buffer = ByteBuffer.allocate(size());
      buffer.order(ByteOrder.LITTLE_ENDIAN);
      buffer.putShort((short) headerSize());
      buffer.putShort((short) flags());
      buffer.putInt(keyIndex());
      if (isComplex()) {
        buffer.putInt(parentEntry());
        buffer.putInt(values().size());
        for (Map.Entry<Integer, ResourceValue> entry : values().entrySet()) {
          buffer.putInt(entry.getKey());
          buffer.put(entry.getValue().toByteArray(shrink));
        }
      } else {
        ResourceValue value = value();
        Preconditions.checkNotNull(value, "A non-complex TypeChunk entry must have a value.");
        buffer.put(value.toByteArray());
      }
      return buffer.array();
    }

    @Override
    public final String toString() {
      return String.format("Entry{key=%s}", key());
    }
  }
}
