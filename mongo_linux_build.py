import os
import traceback
import sys
import platform
import locale
import podman
import subprocess

docker_image = 'redhat/ubi8:8.10-1184'

podman_cmds = [
    f"sudo podman pull docker.io/{docker_image}",
    "sudo systemctl --user enable podman.socket",
    "sudo systemctl --user start podman.socket",
    "sudo systemctl --user status podman.socket",
]

for cmd in podman_cmds:
    subprocess.run(cmd.split(' '))

client = podman.from_env()
try:
    container = client.containers.get("bazel_build")
except:
    pass
else:
    try:
        container.stop()
    except:
        pass
    try:
        container.remove()
    except:
        pass

reported_arch = platform.machine().lower()
if reported_arch in ["aarch64", "arm64"]:
    bazel_url = "https://github.com/bazelbuild/bazel/releases/download/7.5.0/bazel-7.5.0-linux-arm64"
elif reported_arch in ["x86_64", "amd64"]:
    bazel_url = "https://github.com/bazelbuild/bazel/releases/download/7.5.0/bazel-7.5.0-linux-x86_64"
elif reported_arch == "s390x":
    bazel_url = "https://mdb-build-public.s3.amazonaws.com/bazel-binaries/7.5.0/bazel-7.5.0-linux-s390x"
elif reported_arch == "ppc64le":
    bazel_url = "https://mdb-build-public.s3.amazonaws.com/bazel-binaries/7.5.0/bazel-7.5.0-linux-ppc64le"

container = client.containers.run(
        image = "redhat/ubi8:8.10-1184", 
        name="bazel_build", 
        mounts=[{
            "type": "bind",
            "source": os.path.join(os.getcwd(), "src"),
            "target": "/tmp/bazel",
            "read_only": False
        }],
        network='host',
        tty=True,
        detach=True,
    )
try:
    cmds = [
        'yum install -y gcc gcc-c++ python3 zip java-21-openjdk-devel',
        f'curl -L {bazel_url} -o bazel_bootstrap',
        'chmod +x ./bazel_bootstrap',
        './bazel_bootstrap build //src:bazel --compilation_mode=opt',
        'cp bazel-bin/src/bazel mongo_bazel'
    ]
    
    for cmd in cmds:
        print(f"cmd:\n{cmd}")
        print("output:")
        result = container.exec_run(cmd, workdir="/tmp/bazel")
        print(result[1].decode("latin1"))
      
            
except:
    traceback.print_exc()
try:
    container.stop()
except:
    pass
try:
    container.remove()
except:
    pass
    