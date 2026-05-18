# Clouseau Docker Image

## Quick Start

### Using Pre-built Image (Recommended)
```sh
docker pull ghcr.io/cloudant-labs/clouseau:latest
```

### Building from Source

```sh
docker build -t clouseau:latest .
```
This downloads the official release from [releases](github.com/cloudant-labs/clouseau/releases).

## For Contributors

### Building with Local Changes
For contributors who want to test local changes:

```sh
# First, build the artifacts
make -C .. artifacts

# Then build the Docker image
docker build --build-arg BUILD_MODE=local -t clouseau:dev .
```

### Running with Docker Compose

```sh
# Generate an Erlang cookie for secure node communication:
make -C .. generate-erlang-cookie
docker compose -f compose.yaml up
```

### Running with Podman Compose

**Prerequisites:** Before running Podman Compose, the following configuration changes are required:

1. Modify the `name` parameter in the Dreyfus configuration section of `local.ini` according to the provided comments.
2. Set `config.node.domain` to `clouseau.dns.podman` in `app.conf`.

Once the configuration is complete, start the services:

```sh
# Generate an Erlang cookie for secure node communication:
make -C .. generate-erlang-cookie
podman compose -f compose.yaml up
```
