#
# Copyright (C) 2017 Dremio Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
ARG TARBALL_PATH=dremio.tar.gz
ARG JAVA_IMAGE="eclipse-temurin:17-jre-jammy"
FROM busybox AS extractor
ARG TARBALL_PATH
COPY ${TARBALL_PATH} /tmp/dremio.tar.gz
RUN mkdir -p /opt/dremio \
    && tar xzf /tmp/dremio.tar.gz -C /opt/dremio --strip-components=1 \
    && rm /tmp/dremio.tar.gz \
    && rm -f /opt/dremio/jars/dremio-ce-jdbc-plugin-*.jar

FROM ${JAVA_IMAGE} AS runtime

LABEL maintainer=Dremio

RUN \
  mkdir -p /opt/dremio \
  && mkdir -p /var/lib/dremio \
  && mkdir -p /var/run/dremio \
  && mkdir -p /var/log/dremio \
  && mkdir -p /opt/dremio/data \
  \
  && groupadd --system dremio --gid 999 \
  && useradd --base-dir /var/lib/dremio --system --uid 999 --gid dremio dremio \
  && chown -R dremio:dremio /opt/dremio/data \
  && chown -R dremio:dremio /var/run/dremio \
  && chown -R dremio:dremio /var/log/dremio \
  && chown -R dremio:dremio /var/lib/dremio

COPY --from=extractor --chown=dremio:dremio /opt/dremio /opt/dremio

# Install native ADBC PostgreSQL driver for high-performance Arrow-native query execution.
# The JNI bridge is bundled inside adbc-driver-jni.jar (self-extracting),
# but the database-specific native driver .so must be installed separately.
# We use the dbc CLI (from columnar.tech) to install the driver, falling back
# to a direct GitHub release download if the CLI is unavailable.
RUN mkdir -p /opt/adbc/drivers \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && ( curl -LsSf https://dbc.columnar.tech/install.sh | sh \
         && export PATH="$HOME/.cargo/bin:$PATH" \
         && dbc install postgresql \
         && cp /root/.config/columnar/adbc_drivers/*.so /opt/adbc/drivers/ 2>/dev/null \
       ) \
    || ( echo "dbc CLI unavailable, downloading ADBC driver from GitHub releases..." \
         && curl -L https://github.com/apache/arrow-adbc/releases/download/apache-arrow-adbc-22/adbc_driver_postgresql-1.4.0-linux-amd64.tar.gz \
            | tar xz -C /opt/adbc/drivers/ 2>/dev/null \
         || echo "WARN: ADBC native driver download failed; ADBC mode will fall back to JDBC at runtime" \
       ) \
    && chmod -R 755 /opt/adbc/drivers \
    && apt-get remove -y curl \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*

# Make ADBC native drivers discoverable by the JNI driver manager.
ENV LD_LIBRARY_PATH="/opt/adbc/drivers:${LD_LIBRARY_PATH}"

EXPOSE 9047/tcp
EXPOSE 31010/tcp
EXPOSE 32010/tcp
EXPOSE 45678/tcp

USER dremio
WORKDIR /opt/dremio
ENV DREMIO_HOME /opt/dremio
ENV DREMIO_PID_DIR /var/run/dremio
ENV DREMIO_GC_LOGS_ENABLED="yes"
ENV DREMIO_GC_LOG_TO_CONSOLE="yes"
ENV DREMIO_LOG_DIR="/var/log/dremio"
ENTRYPOINT ["bin/dremio", "start-fg"]
