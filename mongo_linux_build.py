import docker
import os
import traceback
import sys
import platform

client = docker.from_env()
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
    arch = "arm64"
elif reported_arch in ["x86_64", "amd64"]:
    arch = "x86_64"
elif reported_arch == "s390x":
    arch = "s390x"
elif reported_arch == "ppc64le":
    arch = "ppc64le"

container = client.containers.run(
        image = "redhat/ubi8:latest", 
        name="bazel_build", 
        volumes={
            os.getcwd(): {
                'bind': "/tmp/bazel",
                'mode': 'rw',
            }
        },
        network='host',
        tty=True,
        detach=True,
        entrypoint="/bin/bash",

    )
try:
    cmds = [
        'yum install -y gcc gcc-c++ python3 zip java-21-openjdk',
        f'curl -L https://github.com/bazelbuild/bazel/releases/download/7.5.0/bazel-7.5.0-linux-{arch} -o bazel_bootstrap',
        'chmod +x ./bazel_bootstrap',
        './bazel_bootstrap build //src:bazel-dev',
        'cp bazel-bin/src/bazel-dev mongo_bazel'
    ]
    
    for cmd in cmds:
        print(f"cmd:\n{cmd}")
        print("output:")
        result = container.exec_run(cmd, workdir="/tmp/bazel", stream=True, demux=True)
        for stream in result:
            if stream:
                for chunk in stream:
                    if chunk[0]:
                        sys.stdout.write(chunk[0].decode())
                    if chunk[1]:
                        sys.stdout.write(chunk[1].decode())
            
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
    