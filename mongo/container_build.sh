#!/bin/bash

set -o errexit
set -o verbose

yum install -y gcc gcc-c++ python3 zip java-21-openjdk-devel
curl -L "$1" -o bazel_bootstrap
chmod +x ./bazel_bootstrap
./bazel_bootstrap build //src:bazel --compilation_mode=opt --subcommands --verbose_failures
cp bazel-bin/src/bazel mongo_bazel
