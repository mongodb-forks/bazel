#!/bin/bash

set -o errexit
set -o verbose

curl -L $1 -o bootstrap_bazel$2
chmod +x "./bootstrap_bazel$2"
echo "//src:bazel$2" > target.file
"./bootstrap_bazel$2" --output_base=$PWD/bazel_output_base build --compilation_mode=opt --subcommands --verbose_failures --target_pattern_file="target.file"
cp "bazel-bin/src/bazel$2" "mongo_bazel$2"
"./mongo_bazel$2" info