#!/bin/bash

set -o errexit
set -o verbose

yum install -y gcc gcc-c++ gcc-toolset-15-gcc gcc-toolset-15-gcc-c++ \
  python3.11 zip unzip which java-21-openjdk-devel

# ubi8's default gcc is 8.5 (2018). RHEL 8 ships newer compilers as gcc-toolset,
# so build with 15.2 instead. Bazel's C++ toolchain autoconfiguration picks these
# up from CC/CXX, and the absolute paths keep working in actions, which run with
# a stripped environment.
export CC=/opt/rh/gcc-toolset-15/root/usr/bin/gcc
export CXX=/opt/rh/gcc-toolset-15/root/usr/bin/g++

# ubi8's default python3 is 3.6, and rules_python has no hermetic interpreter for
# s390x or ppc64le, so those builds fall back to the system one -- too old for the
# rules_pkg bootstrap, which needs 3.8+. Make python3 mean 3.11 everywhere.
alternatives --set python3 /usr/bin/python3.11

# turbine_direct_graal is a GraalVM native image and fails to start ("Failed to
# create the main Isolate") on kernels with a 64K page size, which is what the
# aarch64 builders run, so compile headers with javac instead.
common_args=(--nojava_header_compilation)
repo_args=()

case "$(uname -m)" in
  s390x|ppc64le|ppc64|ppc|ppcle|s390)
    # rules_java 9.1.0 only publishes remotejdk 25 for x86_64 and aarch64, so
    # fall back to the newest remote JDK available for these arches.
    common_args+=(--java_runtime_version=remotejdk_21 --tool_java_runtime_version=remotejdk_21)
    # rules_python has no prebuilt interpreter for them either, so it registers no
    # toolchain at all. Fall back to the runtime_env toolchain, which just uses the
    # python3 on PATH -- 3.11, per the alternatives call above.
    common_args+=(--extra_toolchains=@rules_python//python/runtime_env_toolchains:all)
    # rules_java's registered java toolchains hardcode a JDK 25 runtime that does
    # not exist here, so prefer our own. See mongo/toolchains/BUILD.
    repo_args+=(--extra_toolchains=//mongo/toolchains:jdk21_toolchain_definition)

    # The Temurin jmods archive needed to jlink the embedded JDK is not on
    # mirror.bazel.build, and bazel's downloader gets "Connection refused"
    # reaching github.com from these builders -- though curl works. Pre-fetch it
    # into a distdir, where bazel picks it up by basename and verifies the hash.
    # Keep in sync with openjdk_linux_*_jmods in //:repositories.bzl.
    jmods_arch=ppc64le
    [ "$(uname -m)" = s390x ] && jmods_arch=s390x
    jmods_file="OpenJDK25U-jmods_${jmods_arch}_linux_hotspot_25.0.2_10.tar.gz"
    mkdir -p /var/tmp/distdir
    curl -fL -o "/var/tmp/distdir/${jmods_file}" \
      "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.2%2B10/${jmods_file}"
    repo_args+=(--distdir=/var/tmp/distdir)
    ;;
esac

# compile.sh builds the upstream dist archive, which has no mongo/toolchains
# package, so repo_args applies to the main build only.
bootstrap_args=("${common_args[@]}")
bazel_args=("${common_args[@]}" "${repo_args[@]}")

if [[ "$1" == *.zip ]]; then
  # No prebuilt bazel is published for this architecture, so bootstrap one from
  # the release dist archive with compile.sh instead.
  curl -fL "$1" -o bazel_dist.zip
  rm -rf /var/tmp/bazel_dist
  mkdir -p /var/tmp/bazel_dist
  unzip -q bazel_dist.zip -d /var/tmp/bazel_dist

  # The dist archive pins the same protobuf 33.4 and carries its own MODULE.bazel,
  # so it needs the big-endian upb fix too or the bootstrap crashes on s390x.
  cp third_party/protobuf-bigendian.patch /var/tmp/bazel_dist/third_party/
  sed -i 's|patches = \["//third_party:protobuf.patch"\],|patches = ["//third_party:protobuf.patch", "//third_party:protobuf-bigendian.patch"],|' \
    /var/tmp/bazel_dist/MODULE.bazel
  grep -q "protobuf-bigendian.patch" /var/tmp/bazel_dist/MODULE.bazel || {
    echo "Failed to add the big-endian protobuf patch to the dist MODULE.bazel" >&2
    exit 1
  }

  (cd /var/tmp/bazel_dist && EXTRA_BAZEL_ARGS="${bootstrap_args[*]}" bash ./compile.sh)
  cp /var/tmp/bazel_dist/output/bazel bazel_bootstrap
else
  curl -fL "$1" -o bazel_bootstrap
fi

# Fail loudly if we did not end up with an executable (e.g. S3 returned an XML
# error document instead of the bootstrap binary).
if ! head -c 4 "bazel_bootstrap" | grep -q $'\x7fELF'; then
  echo "Bootstrap bazel from $1 is not an ELF executable:" >&2
  head -c 512 "bazel_bootstrap" >&2
  exit 1
fi
chmod +x ./bazel_bootstrap
./bazel_bootstrap build "${bazel_args[@]}" --compilation_mode=opt --subcommands --verbose_failures --stamp --embed_label=$2 //src:bazel
cp bazel-bin/src/bazel "$3"
sha256sum -b "$3" > "${3}.sha256"
