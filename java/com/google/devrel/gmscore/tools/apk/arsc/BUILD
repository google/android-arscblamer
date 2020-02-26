""" Copyright 2016 Google Inc. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. """

# Tool for reassembling and understanding arsc files.
package(default_visibility = ["//visibility:public"])

java_binary(
    name = "ArscDumper",
    main_class = "com.google.devrel.gmscore.tools.apk.arsc.ArscDumper",
    runtime_deps = [
        ":arsc",
    ],
)

java_library(
    name = "arsc",
    srcs = glob(["*.java"]),
    deps = [
        "//java/com/google/devrel/gmscore/tools/common",
        "//java/com/google/devrel/gmscore/tools/common/flags",
        "//third_party/java/auto:auto_value",
        "//third_party/java/checkerframework",
        "//third_party/java/guava",
        "//third_party/java/guava_failureaccess",
        "//third_party/java/guice",
        "//third_party/java/javaxannotation",
        "//third_party/java/jcommander",
        "//third_party/java/jsr305_annotations",
        "//third_party/java/jsr330_inject",
        "//third_party/java/opencsv",
    ],
)
