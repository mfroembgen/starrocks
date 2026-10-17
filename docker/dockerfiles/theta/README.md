# Native 4.1.6 Theta images

This fork starts at upstream StarRocks `4.1.6`, commit
`6862092fd57e95a892fdfd465ac4ae3a9beefbe1`.
The manually dispatched [Native Theta images workflow](../../../.github/workflows/theta-images.yml)
tests and builds the selected fork commit separately on native AMD64 and ARM64
GitHub runners. It does not use CPU emulation or access a deployed cluster.

Use a homogeneous fork BE/CN worker set for each query. Stock 4.1.6 cannot read
this fork's `[0xFE][log_k]` partial or persisted Theta states and silently ignores
them, so mixing stock and fork workers can undercount. Before rolling back to
stock, convert or rematerialize framed stored states. Compatibility with the
prior Theta fork's framing does not make a stock rollback safe.

## Images and provenance

The workflow publishes these repositories:

- `ghcr.io/mfroembgen/starrocks-fe`
- `ghcr.io/mfroembgen/starrocks-be`
- `ghcr.io/mfroembgen/starrocks-cn`

Each receives `4.1.6-theta-<full-source-SHA>-amd64` and `-arm64` tags, followed by
the `4.1.6-theta-<full-source-SHA>` multiarch index after both platforms pass.
Deploy by the recorded **index digest**, which identifies the exact build.
Source tags identify a source revision; rerunning a build can produce different
digests because upstream embeds build timestamps and Maven resolves dependencies.
There is no floating `latest` or upstream `4.1.6` replacement tag.

BE and CN publish the same shared-data-enabled backend payload under both role
names. They retain upstream `/opt/starrocks/be` and `/opt/starrocks/cn -> be`,
`be_entrypoint.sh`, and `cn_entrypoint.sh`. No second backend compilation is needed.

Both binaries embed the full source SHA, and OCI labels record the fork source,
source SHA, version and upstream base commit. Architecture digests and verified
multiarch indexes are retained in the workflow artifacts and job summary.
Publishing uses only the workflow's `GITHUB_TOKEN` with `packages: write`.
New personal GHCR packages initially have private visibility even when linked to
a public repository. After their first publication, set each package to public
in its GitHub package settings and verify anonymous pulls before deployment.

## Build inputs and gates

The Dockerfile pins the official `starrocks/dev-env-ubuntu:4.1.6` multiarch index
and the official `starrocks/{fe,be}-ubuntu:4.1.6` runtime indexes by digest. The
builder supplies Ubuntu 22.04, Java 17 and the matching prebuilt thirdparty/starlet
dependencies. Runtime images preserve upstream startup scripts, dependencies and
profiling tools, replacing their FE/BE binaries, libraries and configuration with
the source build. Component copies are restricted to `output/fe` and `output/be`.

Both architectures must pass the selected FE semantic/planner/JUnit classes and
the BE `DataSketchsThetaTest` suite before any image publication. XML report
checks require every selected suite to execute tests with no failures or errors;
reports are retained as workflow artifacts. Image builds
verify embedded commit metadata, then start a temporary FE and BE and assert SQL
Theta count and serialized-state estimate with a NULL input. This local smoke
test does not validate an object store, IAM, shared-data storage, or a production
workload; those need the separately authorized deployment validation.

## Capacity and local reproduction

Public GitHub runners have four CPUs and 16 GiB RAM. The workflow removes unused
preinstalled SDKs on its disposable runners and requires at least 40 GiB free
disk before compiling with `-j 2`. BE unit tests and image builds use separate
jobs, each with a six-hour limit. Upstream's BE test selector filters execution
but still compiles the complete BE test binary. A timeout or capacity failure
must fail the publication gate; use a suitably sized native runner if needed.

From a clean checkout of the commit to build, on a matching Linux native CPU:

```bash
source_sha=$(git rev-parse HEAD)
docker buildx build --progress=plain --build-arg SOURCE_SHA="$source_sha" --target fe-tests -f docker/dockerfiles/theta/Dockerfile .
docker buildx build --progress=plain --build-arg SOURCE_SHA="$source_sha" --target be-tests -f docker/dockerfiles/theta/Dockerfile .
docker buildx build --load --build-arg SOURCE_SHA="$source_sha" --target fe -t theta-fe:local -f docker/dockerfiles/theta/Dockerfile .
docker buildx build --load --build-arg SOURCE_SHA="$source_sha" --target be -t theta-be:local -f docker/dockerfiles/theta/Dockerfile .
bash docker/dockerfiles/theta/smoke.sh theta-fe:local theta-be:local
```

The previous public `j4k4/starrocks` fork added no native multiarch release
workflow. Its artifact Dockerfile isolated the FE and BE output directories; this
build preserves that isolation without the upstream artifact image's floating
DataDog/Arthas downloads or unrelated broker build.

Sources: [upstream Docker build guide](https://docs.starrocks.io/docs/developers/build-starrocks/Build_in_docker/),
[upstream 4.1.6 runtime Dockerfiles](https://github.com/StarRocks/starrocks/tree/4.1.6/docker/dockerfiles),
[GitHub runner resources](https://docs.github.com/en/actions/reference/runners/github-hosted-runners),
[GHCR visibility](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility).
