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

package com.google.devrel.gmscore.tools.common.flags;

import static com.google.common.base.Preconditions.checkState;

import com.google.devrel.gmscore.tools.common.flags.BindingAnnotations.ApkPath;
import com.google.devrel.gmscore.tools.common.flags.BindingAnnotations.ArscPath;
import com.google.devrel.gmscore.tools.common.flags.BindingAnnotations.ClientLibs;
import com.google.devrel.gmscore.tools.common.flags.BindingAnnotations.DxPath;
import com.google.devrel.gmscore.tools.common.flags.BindingAnnotations.InputPath;
import com.google.devrel.gmscore.tools.common.flags.BindingAnnotations.JavaPath;
import com.google.devrel.gmscore.tools.common.flags.BindingAnnotations.NewFilePath;
import com.google.devrel.gmscore.tools.common.flags.BindingAnnotations.OldFilePath;
import com.google.devrel.gmscore.tools.common.flags.BindingAnnotations.OutputPath;
import com.google.devrel.gmscore.tools.common.flags.BindingAnnotations.OutputPrefix;
import com.google.devrel.gmscore.tools.common.flags.BindingAnnotations.Verbose;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import java.io.File;

import javax.annotation.Nullable;

/**
 * Class to hold all parameters to avoid parameter duplication.
 */
@Parameters(separators = " =")
public class CommonParams {

  @ApkPath
  @Parameter(names = "--apk", description = "Path to apk file to analyze")
  @Nullable
  private File apk;

  @OldFilePath
  @Parameter(names = "--old", description = "The old file")
  @Nullable
  private File oldFile;

  @NewFilePath
  @Parameter(names = "--new", description = "The new file")
  @Nullable
  private File newFile;

  @ArscPath
  @Parameter(names = "--arsc", description = "Path to arsc file to analyze")
  @Nullable
  private File arsc;

  @ClientLibs
  @Parameter(names = "--client_libs", description = "Path to client libs.")
  private String clientLibs = "";

  @DxPath
  @Parameter(names = "--dx_path", description = "Path to 'dx' jar")
  private String dxPath = "";

  @JavaPath
  @Parameter(names = "--java_path", description = "Path to 'java' command")
  private String javaPath = "/usr/bin/java";

  @InputPath
  @Parameter(names = "--input", description = "File or directory that input should be read from.")
  @Nullable
  private File input;

  @OutputPath
  @Parameter(names = "--output", description = "File or directory that output should be written to.")
  @Nullable
  private File output;

  @OutputPrefix
  @Parameter(names = "--outputFilePrefix", description = "Prefix for output file.")
  private String outputFilePrefix = "GmsCoreStats";

  @Verbose
  @Parameter(names = "--verbose", description = "Print additional debugging information")
  private Boolean verbose = false;

  @Nullable
  public File getOutput() {
    return output;
  }

  public void validateFlags() {
    checkState(apk != null, "--apk not set");
    checkState(clientLibs != null, "--client_libs not set");
    checkState(output != null, "--output not set");
  }
}
