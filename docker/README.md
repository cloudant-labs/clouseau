# Clouseau Docker Image

This directory contains the sources to build and run container images for Clouseau.

## Quick Start

Before you begin, make sure you are in the root directory of this repository (not the `docker/` subdirectory, or the `make` commands below will fail).
```sh
cd /path/to/clouseau   # your local git clone of this repository
```

### Using Pre-built Image (Recommended)
```sh
docker pull ghcr.io/cloudant-labs/clouseau:latest
```

### Building from the Published Release

```sh
make docker-build
```
This downloads the official release artifact from [releases](https://github.com/cloudant-labs/clouseau/releases) — it does **not** use the source code in your local clone.

To build an image from your local source instead (e.g. to test uncommitted changes), go to the [Building with Local Changes](#building-with-local-changes) section.

## For Contributors

### Building with Local Changes
For contributors who want to test local changes:

```sh
# First, build the artifacts
make artifacts

# Then build the Docker image
make docker-build MODE=local
```

### Running Docker Compose

```sh
# Generate an Erlang cookie for secure node communication:
make generate-erlang-cookie
make docker-compose-up
```
