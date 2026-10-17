# syntax=docker/dockerfile:1
# OHIF Viewer v3 (official image), relocated from / to /ohif/ so it is served on the same origin
# as RDSS behind the frontend nginx.
#
# The upstream build hard-codes "/" in three places, all patched here and verified at build time:
#   1. index.html asset URLs and window.PUBLIC_URL
#   2. the webpack runtime public path (__webpack_require__.p) in the app and worker bundles,
#      which controls where lazily loaded chunks, workers and codecs are fetched from
#   3. font url(/...) references in app.bundle.css
# Upstream ships empty *.js/*.css placeholders and serves the real files from *.gz via
# "gzip_static always", so the patching happens on the decompressed .gz payloads.
ARG OHIF_IMAGE=ohif/app:v3.13.13

FROM ${OHIF_IMAGE} AS upstream

FROM alpine:3.20 AS patch
RUN apk add --no-cache sed gzip grep
COPY --from=upstream /usr/share/nginx/html /src
COPY app-config.js /src/app-config.js
WORKDIR /src
RUN set -eux; \
    rm -f app-config.js.gz google.js google.js.gz index.html.gz sw.js sw.js.gz; \
    sed -i \
      -e 's#window.PUBLIC_URL="/"#window.PUBLIC_URL="/ohif/"#' \
      -e 's#href="/#href="/ohif/#g' \
      -e 's#src="/#src="/ohif/#g' \
      -e 's#content="/assets#content="/ohif/assets#g' \
      index.html; \
    grep -q 'window.PUBLIC_URL="/ohif/"' index.html; \
    patched=0; \
    for gz in *.js.gz *.css.gz; do \
      gunzip -c "$gz" > /tmp/f; \
      if grep -Eq '__webpack_require__\.p[[:space:]]*=[[:space:]]*"/"|url\(/' /tmp/f; then \
        sed -E -i \
          -e 's#(__webpack_require__\.p[[:space:]]*=[[:space:]]*)"/"#\1"/ohif/"#g' \
          -e 's#url\(/([^/])#url(/ohif/\1#g' \
          /tmp/f; \
        ! grep -Eq '__webpack_require__\.p[[:space:]]*=[[:space:]]*"/"' /tmp/f; \
        gzip -9 -c /tmp/f > "$gz"; \
        patched=$((patched + 1)); \
      fi; \
    done; \
    echo "patched $patched bundles"; \
    test "$patched" -ge 2; \
    gzip -k -f index.html

FROM ${OHIF_IMAGE}
USER root
RUN rm -rf /usr/share/nginx/html/*
COPY --from=patch --chown=nginx:nginx /src /usr/share/nginx/html/ohif
USER nginx
# The upstream entrypoint renders its nginx template with ${PUBLIC_URL} (SPA fallback to
# /ohif/index.html) and compresses app-config.js at ${PUBLIC_URL}app-config.js.
ENV PUBLIC_URL=/ohif/ \
    PORT=8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/ohif/app-config.js >/dev/null || exit 1
