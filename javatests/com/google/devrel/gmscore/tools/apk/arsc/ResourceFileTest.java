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

import static com.google.common.truth.Truth.assertThat;

import com.google.devrel.gmscore.tools.common.ApkUtils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.util.Map;
import java.util.Map.Entry;

@RunWith(JUnit4.class)
/** Tests {@link ResourceFile}. */
public final class ResourceFileTest {

  private static final String APK_FILE_PATH =
      "javatests/com/google/devrel/gmscore/tools/common/data/Topeka.apk";
  private static final File apkFile = new File(APK_FILE_PATH);

  /** Tests that resource files, when reassembled, are identical. */
  @Test
  public void testToByteArray() throws Exception {
    // Get all .arsc and encoded .xml files
    String regex = "(.*?\\.arsc)|(AndroidManifest\\.xml)|(res/.*?\\.xml)";
    Map<String, byte[]> resourceFiles = ApkUtils.getFiles(apkFile, regex);
    for (Entry<String, byte[]> entry : resourceFiles.entrySet()) {
      String name = entry.getKey();
      byte[] fileBytes = entry.getValue();
      if (!name.startsWith("res/raw/")) {  // xml files in res/raw/ are not compact XML
        ResourceFile file = new ResourceFile(fileBytes);
        assertThat(file.toByteArray()).named(name).isEqualTo(fileBytes);
      }
    }
  }
}
