# Sourced by the docker/*.sh scripts.
# Builds the toolchain image, then deletes old untagged versions of it so repeated builds
# don't fill the disk. No `docker builder prune`: it is global and would drop the build
# cache that lets this image share its SDK layers with go-data-android.
IMAGE=dnsflow-build
build_image() {
  docker build -q -t "$IMAGE" -f docker/Dockerfile docker >/dev/null
  docker image prune -f --filter label=project=dnsflow >/dev/null
}
