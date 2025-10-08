#!/bin/bash

set -o errexit
set -o verbose

yum install -y rh-python36 zip unzip
export CC=$(which gcc)
curl -L "$1" -o bazel_bootstrap
chmod +x ./bazel_bootstrap
scl enable rh-python36 devtoolset-7 './bazel_bootstrap build  --compilation_mode=opt --subcommands --verbose_failures --stamp --embed_label=$2 //src:bazel'
cp bazel-bin/src/bazel "$3"
sha256sum -b "$3" > "${3}.sha256"
