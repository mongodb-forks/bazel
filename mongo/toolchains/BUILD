# rules_java's registered toolchain_jdk_* toolchains hardcode
# java_runtime = ":remotejdk_25" (see @rules_java//toolchains/BUILD), and no
# remotejdk 25 is published for s390x or ppc64le. Define an equivalent toolchain
# that leaves java_runtime unset so it resolves from --tool_java_runtime_version,
# and register it with --extra_toolchains on those architectures.

load("@rules_java//toolchains:default_java_toolchain.bzl", "DEFAULT_TOOLCHAIN_CONFIGURATION", "default_java_toolchain")

default_java_toolchain(
    name = "jdk21_toolchain",
    configuration = DEFAULT_TOOLCHAIN_CONFIGURATION,
    source_version = "21",
    target_version = "21",
    visibility = ["//visibility:public"],
)
