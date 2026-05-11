#!/bin/sh
# DEPRECATED — nginx no longer terminates Basic Auth. The login flow is
# handled by the React SPA, which attaches an ``Authorization: Basic …``
# header to every /api/* request from sessionStorage. The Java backend
# validates it.
#
# This file is kept around so old container images that still copy it
# into /docker-entrypoint.d/ don't fail; the current Dockerfile no
# longer references it.
exit 0
