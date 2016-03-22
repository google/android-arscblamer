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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.io.LittleEndianDataOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/** Represents a string pool structure. */
public final class StringPoolChunk extends Chunk {

  // These are the defined flags for the "flags" field of ResourceStringPoolHeader
  private static final int SORTED_FLAG = 1 << 0;
  private static final int UTF8_FLAG   = 1 << 8;

  /** The offset from the start of the header that the stylesStart field is at. */
  private static final int STYLE_START_OFFSET = 24;

  /** Flags. */
  private final int flags;

  /** Index from header of the string data. */
  private final int stringsStart;

  /** Index from header of the style data. */
  private final int stylesStart;

  /**
   * Number of strings in the original buffer. This is not necessarily the number of strings in
   * {@code strings}.
   */
  private final int stringCount;

  /**
   * Number of styles in the original buffer. This is not necessarily the number of styles in
   * {@code styles}.
   */
  private final int styleCount;

  /**
   * The strings ordered as they appear in the arsc file. e.g. strings.get(1234) gets the 1235th
   * string in the arsc file.
   */
  private final List<String> strings = new ArrayList<>();

  /**
   * These styles have a 1:1 relationship with the strings. For example, styles.get(3) refers to
   * the string at location strings.get(3). There are never more styles than strings (though there
   * may be less). Inside of that are all of the styles referenced by that string.
   */
  private final List<StringPoolStyle> styles = new ArrayList<>();

  /**
   * True if the original {@link StringPoolChunk} shows signs of being deduped. Specifically, this
   * is set to true if there exists a string whose offset is <= the previous offset. This is used to
   * preserve the deduping of strings for pools that have been deduped.
   */
  private boolean isOriginalDeduped = false;

  protected StringPoolChunk(ByteBuffer buffer, @Nullable Chunk parent) {
    super(buffer, parent);
    stringCount = buffer.getInt();
    styleCount = buffer.getInt();
    flags        = buffer.getInt();
    stringsStart = buffer.getInt();
    stylesStart  = buffer.getInt();
  }

  @Override
  protected void init(ByteBuffer buffer) {
    super.init(buffer);
    strings.addAll(readStrings(buffer, offset + stringsStart, stringCount));
    styles.addAll(readStyles(buffer, offset + stylesStart, styleCount));
  }

  /**
   * Returns the 0-based index of the first occurrence of the given string, or -1 if the string is
   * not in the pool. This runs in O(n) time.
   *
   * @param string The string to check the pool for.
   * @return Index of the string, or -1 if not found.
   */
  public int indexOf(String string) {
    return strings.indexOf(string);
  }

  /**
   * Returns a string at the given (0-based) index.
   *
   * @param index The (0-based) index of the string to return.
   * @throws IndexOutOfBoundsException If the index is out of range (index < 0 || index >= size()).
   */
  public String getString(int index) {
    return strings.get(index);
  }

  /** Returns the number of strings in this pool. */
  public int getStringCount() {
    return strings.size();
  }

  /**
   * Returns a style at the given (0-based) index.
   *
   * @param index The (0-based) index of the style to return.
   * @throws IndexOutOfBoundsException If the index is out of range (index < 0 || index >= size()).
   */
  public StringPoolStyle getStyle(int index) {
    return styles.get(index);
  }

  /** Returns the number of styles in this pool. */
  public int getStyleCount() {
    return styles.size();
  }

  /** Returns the type of strings in this pool. */
  public ResourceString.Type getStringType() {
    return isUTF8() ? ResourceString.Type.UTF8 : ResourceString.Type.UTF16;
  }

  @Override
  protected Type getType() {
    return Chunk.Type.STRING_POOL;
  }

  /** Returns the number of bytes needed for offsets based on {@code strings} and {@code styles}. */
  private int getOffsetSize() {
    return (strings.size() + styles.size()) * 4;
  }

  /**
   * True if this string pool contains strings in UTF-8 format. Otherwise, strings are in UTF-16.
   *
   * @return true if @{code strings} are in UTF-8; false if they're in UTF-16.
   */
  public boolean isUTF8() {
    return (flags & UTF8_FLAG) != 0;
  }

  /**
   * True if this string pool contains already-sorted strings.
   *
   * @return true if @{code strings} are sorted.
   */
  public boolean isSorted() {
    return (flags & SORTED_FLAG) != 0;
  }

  private List<String> readStrings(ByteBuffer buffer, int offset, int count) {
    List<String> result = new ArrayList<>();
    int previousOffset = -1;
    // After the header, we now have an array of offsets for the strings in this pool.
    for (int i = 0; i < count; ++i) {
      int stringOffset = offset + buffer.getInt();
      result.add(ResourceString.decodeString(buffer, stringOffset, getStringType()));
      if (stringOffset <= previousOffset) {
        isOriginalDeduped = true;
      }
      previousOffset = stringOffset;
    }
    return result;
  }

  private List<StringPoolStyle> readStyles(ByteBuffer buffer, int offset, int count) {
    List<StringPoolStyle> result = new ArrayList<>();
    // After the array of offsets for the strings in the pool, we have an offset for the styles
    // in this pool.
    for (int i = 0; i < count; ++i) {
      int styleOffset = offset + buffer.getInt();
      result.add(StringPoolStyle.create(buffer, styleOffset, this));
    }
    return result;
  }

  private int writeStrings(DataOutput payload, ByteBuffer offsets, boolean shrink)
      throws IOException {
    int stringOffset = 0;
    Map<String, Integer> used = new HashMap<>();  // Keeps track of strings already written
    for (String string : strings) {
      // Dedupe everything except stylized strings, unless shrink is true (then dedupe everything)
      if (used.containsKey(string) && (shrink || isOriginalDeduped)) {
        Integer offset = used.get(string);
        offsets.putInt(offset == null ? 0 : offset);
      } else {
        byte[] encodedString = ResourceString.encodeString(string, getStringType());
        payload.write(encodedString);
        used.put(string, stringOffset);
        offsets.putInt(stringOffset);
        stringOffset += encodedString.length;
      }
    }

    // ARSC files pad to a 4-byte boundary. We should do so too.
    stringOffset = writePad(payload, stringOffset);
    return stringOffset;
  }

  private int writeStyles(DataOutput payload, ByteBuffer offsets, boolean shrink)
      throws IOException {
    int styleOffset = 0;
    if (styles.size() > 0) {
      Map<StringPoolStyle, Integer> used = new HashMap<>();  // Keeps track of bytes already written
      for (StringPoolStyle style : styles) {
        if (!used.containsKey(style) || !shrink) {
          byte[] encodedStyle = style.toByteArray(shrink);
          payload.write(encodedStyle);
          used.put(style, styleOffset);
          offsets.putInt(styleOffset);
          styleOffset += encodedStyle.length;
        } else {  // contains key and shrink is true
          Integer offset = used.get(style);
          offsets.putInt(offset == null ? 0 : offset);
        }
      }
      // The end of the spans are terminated with another sentinel value
      payload.writeInt(StringPoolStyle.RES_STRING_POOL_SPAN_END);
      styleOffset += 4;
      // TODO(acornwall): There appears to be an extra SPAN_END here... why?
      payload.writeInt(StringPoolStyle.RES_STRING_POOL_SPAN_END);
      styleOffset += 4;

      styleOffset = writePad(payload, styleOffset);
    }
    return styleOffset;
  }

  @Override
  protected void writeHeader(ByteBuffer output) {
    int stringsStart = getHeaderSize() + getOffsetSize();
    output.putInt(strings.size());
    output.putInt(styles.size());
    output.putInt(flags);
    output.putInt(strings.isEmpty() ? 0 : stringsStart);
    output.putInt(0);  // Placeholder. The styles starting offset cannot be computed at this point.
  }

  @Override
  protected void writePayload(DataOutput output, ByteBuffer header, boolean shrink)
      throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    int stringOffset = 0;
    ByteBuffer offsets = ByteBuffer.allocate(getOffsetSize());
    offsets.order(ByteOrder.LITTLE_ENDIAN);

    // Write to a temporary payload so we can rearrange this and put the offsets first
    try (LittleEndianDataOutputStream payload = new LittleEndianDataOutputStream(baos)) {
      stringOffset = writeStrings(payload, offsets, shrink);
      writeStyles(payload, offsets, shrink);
    }

    output.write(offsets.array());
    output.write(baos.toByteArray());
    if (!styles.isEmpty()) {
      header.putInt(STYLE_START_OFFSET, getHeaderSize() + getOffsetSize() + stringOffset);
    }
  }

  /**
   * Represents all of the styles for a particular string. The string is determined by its index
   * in {@link StringPoolChunk}.
   */
  @AutoValue
  protected abstract static class StringPoolStyle implements SerializableResource {

    // Styles are a list of integers with 0xFFFFFFFF serving as a sentinel value.
    static final int RES_STRING_POOL_SPAN_END = 0xFFFFFFFF;

    public abstract List<StringPoolSpan> spans();

    static StringPoolStyle create(ByteBuffer buffer, int offset, StringPoolChunk parent) {
      Builder<StringPoolSpan> spans = ImmutableList.builder();
      int nameIndex = buffer.getInt(offset);
      while (nameIndex != RES_STRING_POOL_SPAN_END) {
        spans.add(StringPoolSpan.create(buffer, offset, parent));
        offset += StringPoolSpan.SPAN_LENGTH;
        nameIndex = buffer.getInt(offset);
      }
      return new AutoValue_StringPoolChunk_StringPoolStyle(spans.build());
    }

    @Override
    public byte[] toByteArray() throws IOException {
      return toByteArray(false);
    }

    @Override
    public byte[] toByteArray(boolean shrink) throws IOException {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();

      try (LittleEndianDataOutputStream payload = new LittleEndianDataOutputStream(baos)) {
        for (StringPoolSpan span : spans()) {
          byte[] encodedSpan = span.toByteArray(shrink);
          if (encodedSpan.length != StringPoolSpan.SPAN_LENGTH) {
            throw new IllegalStateException("Encountered a span of invalid length.");
          }
          payload.write(encodedSpan);
        }
        payload.writeInt(RES_STRING_POOL_SPAN_END);
      }

      return baos.toByteArray();
    }

    /**
     * Returns a brief description of the contents of this style. The representation of this
     * information is subject to change, but below is a typical example:
     *
     * <pre>"StringPoolStyle{spans=[StringPoolSpan{foo, start=0, stop=5}, ...]}"</pre>
     */
    @Override
    public String toString() {
      return String.format("StringPoolStyle{spans=%s}", spans());
    }
  }

  /** Represents a styled span associated with a specific string. */
  @AutoValue
  protected abstract static class StringPoolSpan implements SerializableResource {

    static final int SPAN_LENGTH = 12;

    public abstract int nameIndex();
    public abstract int start();
    public abstract int stop();
    public abstract StringPoolChunk parent();

    static StringPoolSpan create(ByteBuffer buffer, int offset, StringPoolChunk parent) {
      int nameIndex = buffer.getInt(offset);
      int start = buffer.getInt(offset + 4);
      int stop = buffer.getInt(offset + 8);
      return new AutoValue_StringPoolChunk_StringPoolSpan(nameIndex, start, stop, parent);
    }

    @Override
    public final byte[] toByteArray() {
      return toByteArray(false);
    }

    @Override
    public final byte[] toByteArray(boolean shrink) {
      ByteBuffer buffer = ByteBuffer.allocate(SPAN_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
      buffer.putInt(nameIndex());
      buffer.putInt(start());
      buffer.putInt(stop());
      return buffer.array();
    }

    /**
     * Returns a brief description of this span. The representation of this information is subject
     * to change, but below is a typical example:
     *
     * <pre>"StringPoolSpan{foo, start=0, stop=5}"</pre>
     */
    @Override
    public String toString() {
      return String.format("StringPoolSpan{%s, start=%d, stop=%d}",
          parent().getString(nameIndex()), start(), stop());
    }
  }
}
