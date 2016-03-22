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

import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;

import javax.annotation.Nullable;

/** Represents the start/end of a namespace in an XML document. */
public abstract class XmlNamespaceChunk extends XmlNodeChunk {

  /** A string reference to the namespace prefix. */
  private final int prefix;

  /** A string reference to the namespace URI. */
  private final int uri;

  protected XmlNamespaceChunk(ByteBuffer buffer, @Nullable Chunk parent) {
    super(buffer, parent);
    prefix = buffer.getInt();
    uri = buffer.getInt();
  }

  /** Returns the namespace prefix. */
  public String getPrefix() {
    return getString(prefix);
  }

  /** Returns the namespace URI. */
  public String getUri() {
    return getString(uri);
  }

  @Override
  protected void writePayload(DataOutput output, ByteBuffer header, boolean shrink)
      throws IOException {
    super.writePayload(output, header, shrink);
    output.writeInt(prefix);
    output.writeInt(uri);
  }

  /**
   * Returns a brief description of this namespace chunk. The representation of this information is
   * subject to change, but below is a typical example:
   *
   * <pre>
   * "XmlNamespaceChunk{line=1234, comment=My awesome comment., prefix=foo, uri=com.google.foo}"
   * </pre>
   */
  @Override
  public String toString() {
    return String.format("XmlNamespaceChunk{line=%d, comment=%s, prefix=%s, uri=%s}",
        getLineNumber(), getComment(), getPrefix(), getUri());
  }
}
