# ArscBlamer
ArscBlamer is a command-line tool that can parse an Android app's resources.arsc file and extract useful, actionable information about its contents.

Features include:
  - Output all resource configurations and see type, variants, size, null entries, entry count, density, and (optionally) the resource names in that configuration.
  - Output all resource names in resources.arsc and see their private, shared, and proportional sizes as well as the configurations they belong to.
  - Output resources which are "baseless" (have no default value).

### Usage
##### Output resource configurations with resource names
`$ bazel run //java/com/google/devrel/gmscore/tools/apk/arsc:ArscDumper -- --apk=/foo/bar.apk --keys > output.csv`
##### Output all resource entries
`$ bazel run //java/com/google/devrel/gmscore/tools/apk/arsc:ArscDumper -- --apk=/foo/bar.apk --type=entries > output.csv`
##### Output all resource entries for resources that have no default configuration
`$ bazel run //java/com/google/devrel/gmscore/tools/apk/arsc:ArscDumper -- --apk=/foo/bar.apk --type=baseless_keys > output.csv`

**NB:** Relative file paths for the APK will not work with `bazel run`. To use relative file paths, see the **Building** section.

### Building
To use ArscBlamer with relative file paths, build the jar with:

`$ bazel build //java/com/google/devrel/gmscore/tools/apk/arsc:ArscDumper_deploy.jar`

### Understanding the output
##### -\-type=configs
 - **Type:** The type of resource configuration (string, attr, id, ...). Different types have different numbers of entries in their entry list.
 - **Config:** The qualifier variants such as `en`, `v21`, `sw600dp`. See [here](http://developer.android.com/guide/topics/resources/providing-resources.html) for a full list.
 - **Size:** The total size of the resource configuration in bytes. This does not include the size of referenced resources, such as strings.
 - **Null Entries:** The number of entries in this resource configuration that are empty. The more there are, the more bytes you can save by removing the configuration.
 - **Entry Count:** The number of actual entries in this configuration. A configuration exists only if it has >= 1 Entry Count. Less usually means the configuration is easier to remove.
 - **Density:** A value from 0 to 1 representing the percent of entries in this configuration that are non-null. (e.g. 0.12 means 12% actual entries and 88% null entries). Lower means the configuration is more wasteful.
 - **Keys:** The resource names in this configuration. These are what need to be removed if you want to remove the configuration. Requires --keys.

##### -\-type=entries or -\-type=baseless_keys
- **Type:** The type of resource configuration (string, attr, id, ...). Different types have different numbers of entries in their entry list.
- **Name:** The resource entry's name.
- **Private Size:** The number of bytes that this entry is holding on to. This is how many bytes in resources.arsc will be saved if the entry is removed.
- **Shared Size:** The number of bytes that this entry references which are also referenced from other entries.
- **Proportional Size:** The total size, proportionally, that this entry is responsible for. These values should sum up to roughly the size of `resources.arsc`.
- **Config Count:** The number of configurations this entry is referenced in. Entries, such as strings, which have a **Config Count** of 1 may be a candidate for removal.
- **Configs:** The configurations this resource appears in.

### Helpful tips on parsing a CSV with [Google Sheets](http://docs.google.com/spreadsheets)
 - Click on the filter button to get sortable columns.
 - Click on the Explore button in the bottom-right to get some neat metrics.


# License

```
Copyright 2016 Google Inc. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

# Disclaimer

This is not an official Google product
