load("@rules_java//java:defs.bzl", "java_test")

package(
    default_applicable_licenses = ["//:license"],
    default_testonly = 1,
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    testonly = 0,
    srcs = glob(["**"]),
    visibility = ["//src:__subpackages__"],
)

java_test(
    name = "grpc",
    srcs = glob(
        ["*.java"],
        exclude = ["SharedConnectionFactoryVirtualThreadPinningTest.java"],
    ),
    tags = [
        "requires-network",
    ],
    test_class = "com.google.devtools.build.lib.AllTests",
    deps = [
        "//src/main/java/com/google/devtools/build/lib/remote/grpc",
        "//src/test/java/com/google/devtools/build/lib:test_runner",
        "//src/test/java/com/google/devtools/build/lib/remote/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:mockito",
        "//third_party:rxjava3",
        "//third_party:truth",
        "//third_party/grpc-java:grpc-jar",
    ],
)

java_test(
    name = "virtual_thread_pinning",
    srcs = ["SharedConnectionFactoryVirtualThreadPinningTest.java"],
    jvm_flags = [
        # Constrain virtual thread carrier pool to 2 threads so that pinning
        # from synchronized blocks in RxJava's BehaviorSubject causes a deadlock
        # deterministically. Without this, the default carrier pool (sized to
        # available processors) may have enough threads to mask the issue.
        "-Djdk.virtualThreadScheduler.parallelism=2",
    ],
    tags = [
        "requires-network",
    ],
    test_class = "com.google.devtools.build.lib.remote.grpc.SharedConnectionFactoryVirtualThreadPinningTest",
    deps = [
        "//src/main/java/com/google/devtools/build/lib/remote/grpc",
        "//src/test/java/com/google/devtools/build/lib/remote/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:mockito",
        "//third_party:rxjava3",
        "//third_party:truth",
        "//third_party/grpc-java:grpc-jar",
    ],
)
