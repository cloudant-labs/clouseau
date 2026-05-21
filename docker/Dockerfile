# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License. You may obtain a copy of
# the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations under
# the License.

ARG CLOUSEAU_VERSION=3.3.0
ARG CLOUSEAU_REPO=https://github.com/cloudant-labs/clouseau
ARG BUILD_MODE=release


# Stage 1: Local build
FROM eclipse-temurin:21-jre-ubi10-minimal AS local-builder
COPY artifacts/*.jar /usr/src/


# Stage 2: Release build
FROM eclipse-temurin:21-jre-ubi10-minimal AS release-builder

ARG CLOUSEAU_VERSION
ARG CLOUSEAU_REPO

RUN apt-get update && \
    apt-get install -y --no-install-recommends unzip && \
    rm -rf /var/lib/apt/lists/*

ADD "${CLOUSEAU_REPO}/releases/download/${CLOUSEAU_VERSION}/clouseau-${CLOUSEAU_VERSION}-dist.zip" /clouseau.zip
ADD "${CLOUSEAU_REPO}/releases/download/${CLOUSEAU_VERSION}/checksums.txt" /checksums.txt

RUN grep "clouseau-${CLOUSEAU_VERSION}-dist.zip" checksums.txt | sha256sum -c - || \
        (echo "ERROR: Checksum verification failed" && exit 1); \
    unzip clouseau.zip -d /usr/src; \
    rm clouseau.zip checksums.txt


# Stage 3: Select builder based on BUILD_MODE
FROM local-builder AS builder-local
FROM release-builder AS builder-release
FROM builder-${BUILD_MODE} AS builder

# Runner stage
FROM eclipse-temurin:21-jre AS runner

# Helpful for `docker inspect`
ARG CLOUSEAU_VERSION
ENV CLOUSEAU_VERSION=${CLOUSEAU_VERSION}
ENV JAVA_XMX=2G
ENV JAVA_XMS=1600M

LABEL maintainer="CouchDB Developers dev@couchdb.apache.org"

# Create the user/group first
RUN groupadd -g 5986 -r clouseau && \
    useradd -u 5986 -d /opt/couchdb-search -g clouseau clouseau

# Install dependencies (Tini)
RUN set -ex; \
    apt-get update; \
    apt-get install -y --no-install-recommends tini; \
    rm -rf /var/lib/apt/lists/*; \
    tini --version

# Set up directories with correct permissions to avoid chmod later
WORKDIR /opt/couchdb-search
RUN chown clouseau:clouseau . && \
    install -d -m 0755 -o clouseau -g clouseau \
    etc \
    lib \
    data

COPY --from=builder --chown=clouseau:clouseau /usr/src/*.jar lib/
COPY --chown=clouseau:clouseau app.conf etc/

VOLUME /opt/couchdb-search/data

USER clouseau

ENTRYPOINT ["tini", "--"]
CMD ["sh", "-c", "\
    exec java \
    -server \
    -Xmx${JAVA_XMX} \
    -Xms${JAVA_XMS} \
    -XX:+UseG1GC \
    -XX:+ParallelRefProcEnabled \
    -Dsun.net.inetaddr.ttl=30 \
    -Dsun.net.inetaddr.negative.ttl=30 \
    -Dcookie.file=/run/secrets/erlang.cookie \
    -classpath /opt/couchdb-search/lib/* \
    com.cloudant.ziose.clouseau.Main \
    /opt/couchdb-search/etc/app.conf"]
