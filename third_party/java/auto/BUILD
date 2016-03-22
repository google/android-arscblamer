package(default_visibility = ["//visibility:public"])

licenses(["notice"])  # Apache 2.0

java_plugin(
    name = "auto_value_processor",
    processor_class = "com.google.auto.value.processor.AutoValueProcessor",
    deps = ["//external:auto_value-jar"],
)

java_library(
    name = "auto_value",
    exported_plugins = [":auto_value_processor"],
    exports = ["//external:auto_value-jar"],
)
