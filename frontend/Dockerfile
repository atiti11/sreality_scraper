# =============================================================================
# Frontend image — multi-stage.
#
# Stage 1: Node build → vite produces /app/dist.
# Stage 2: nginx-alpine → serves dist/ and reverse-proxies /api/* to the
#          backend service (resolved by Docker's internal DNS as `backend`).
#
# Auth at this layer:
#   - None. The React SPA renders a styled login form, stores the
#     credentials in sessionStorage and attaches them as
#     ``Authorization: Basic …`` on every API call. The Java backend
#     validates the header (DASHBOARD_USER / DASHBOARD_PASSWORD env vars).
# =============================================================================

# ---- build stage -----------------------------------------------------------
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install --no-audit --no-fund
COPY . .
RUN npm run build

# ---- runtime stage ---------------------------------------------------------
FROM nginx:1.27-alpine

# wget is needed by the HEALTHCHECK below.
RUN apk add --no-cache wget

COPY --from=build /app/dist /usr/share/nginx/html

# Kept under /etc/nginx/templates/ so the file is run through the nginx
# image's envsubst entrypoint at startup. The filter below matches no env
# var on purpose — nothing currently needs substitution — so the template
# is copied verbatim. Loosen it the day a real placeholder is added.
COPY default.conf.template /etc/nginx/templates/default.conf.template
ENV NGINX_ENVSUBST_FILTER="^_NEVER_MATCHES_DO_NOT_DELETE$"

EXPOSE 80

# /healthz is intentionally unauthenticated so the healthcheck can hit
# it without credentials. See default.conf.template.
HEALTHCHECK --interval=15s --timeout=3s --retries=5 \
    CMD wget -q -O - http://localhost/healthz >/dev/null 2>&1 || exit 1

CMD ["nginx", "-g", "daemon off;"]
