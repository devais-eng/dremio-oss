# Dremio

Dremio enables organizations to unlock the value of their data.

## Table of Contents

1. [Documentation](#documentation)
2. [Quickstart](#quickstart-how-to-build-and-run-dremio)
3. [Docker Image](#docker-image)
4. [Codebase Structure](#codebase-structure)
5. [Samples](#samples)
6. [Contributing](#contributing)
7. [Questions](#questions)

## Documentation

Documentation is available at https://docs.dremio.com.

## Quickstart: How to build and run Dremio

### (a) Prerequisites

* JDK 21 ([OpenJDK](https://adoptium.net/temurin/releases/) or Oracle) as the default JDK (`JAVA_HOME` set to it)
* JDK 17 ([OpenJDK](https://adoptium.net/temurin/releases/) or Oracle) in Maven toolchain, required to run certain integration tests
* JDK 11 ([OpenJDK](https://adoptium.net/temurin/releases/) or Oracle) in Maven toolchain, required to run unit and integration tests
* (Optional) Maven 3.9.3 or later (using Homebrew: `brew install maven`)

Run the following commands to verify that you have the correct versions of Maven and JDK installed:

    java -version
    mvn --version

Add JDK 17 to the Maven toolchain, easiest to use `${HOME}/.m2/toolchains.xml`. Example:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<toolchains>
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>11</version>
      <vendor>sun</vendor>
    </provides>
    <configuration>
      <jdkHome>FULL_PATH_TO_YOUR_JAVA_11_HOME</jdkHome>
    </configuration>
  </toolchain>
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>17</version>
      <vendor>sun</vendor>
    </provides>
    <configuration>
      <jdkHome>FULL_PATH_TO_YOUR_JAVA_17_HOME</jdkHome>
    </configuration>
  </toolchain>
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>21</version>
      <vendor>sun</vendor>
    </provides>
    <configuration>
      <jdkHome>FULL_PATH_TO_YOUR_JAVA_21_HOME</jdkHome>
    </configuration>
  </toolchain>
</toolchains>
```

### (b) Clone the Repository

    git clone https://github.com/dremio/dremio-oss.git dremio

### (c) Build the Code

    cd dremio
    mvn clean install -DskipTests (or ./mvnw clean install -DskipTests if maven is not installed on the machine)

The "-DskipTests" option skips most of the tests. Running all tests takes a long time.

### (d) Run/Install

#### Run

    distribution/server/target/dremio-community-{DREMIO_VERSION}/dremio-community-{DREMIO_VERSION}/bin/dremio start

OR to start a server with a default user (dremio/dremio123)

    mvn compile exec:exec -pl dac/daemon

Once run, the UI is accessible at:

    http://localhost:9047

#### Production Install

##### (1) Unpack the tarball to install.

    mkdir /opt/dremio
    tar xvzf distribution/server/target/*.tar.gz --strip=1 -C /opt/dremio

##### (2) Start Dremio Embedded Mode

    cd /opt/dremio
    bin/dremio

#### OSS Only

To have the best possible experience with Dremio, we include a number of dependencies when building Dremio that are distributed under non-oss free (as in beer) licenses. 
Examples include drivers for major databases such as Oracle Database, Microsoft SQL Server, MySQL as well as enhancements to improve source pushdowns and thread 
scheduling. If you'd like to only include dependencies with OSS licenses, Dremio will continue to work but some features will be unavailable (such as 
connecting to databases that rely on these drivers). 

To build dremio with only OSS dependencies, you can add the following option to your Maven commandline: `-Ddremio.oss-only=true`

The distribution directory will be `distribution/server/target/dremio-oss-{DREMIO_VERSION}/dremio-oss-{DREMIO_VERSION}`

## Docker Image

A Docker image is published to `ghcr.io/devais-eng/dremio-oss` by the [docker-ghcr](.github/workflows/docker-ghcr.yml) GitHub Actions workflow on every version tag push. To build it locally:

### Build the distribution tarball

```bash
./mvnw package -DskipTests -Pdremio.no-lint -pl distribution/server -am
```

### Build the Docker image

```bash
mkdir -p docker-context
cp distribution/server/target/dremio-community-*.tar.gz docker-context/dremio.tar.gz
cp distribution/docker/Dockerfile docker-context/Dockerfile

docker build -t ghcr.io/devais-eng/dremio-oss:latest docker-context/

rm -rf docker-context
```

## Codebase Structure

| Directory                              | Details                                                  |
|----------------------------------------|----------------------------------------------------------|
| [dac](dac/README.md)                   | Dremio Analyst Center - The Dremio management component. |
| [common](common/README.md)             | Dremio Common                                            |
| [distribution](distribution/README.md) | Dremio Distribution                                      |
| [plugins](plugins/README.md)           | Dremio Plugins                                           |

## Samples

| Sample | Description |
|--------|-------------|
| [Iceberg REST Catalog](samples/iceberg-rest-catalog/) | Docker Compose demo with Nessie, MinIO, Keycloak, and RBAC |

## Contributing

If you want to contribute to Dremio, please see [Contributing to Dremio](CONTRIBUTING.md).

## Questions?

If you have questions, please post them on https://community.dremio.com.
