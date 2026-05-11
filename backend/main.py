# DEPRECATED — the dashboard backend has been rewritten in Java
# (Javalin + Jackson + JDBC). The active code lives in
# ``src/main/java/com/sreality/dashboard/``. This file is kept here only
# as a stub so old shell snippets that referenced ``backend/main.py``
# fail clearly instead of silently running stale code.
raise SystemExit(
    "backend/main.py is deprecated. "
    "Run the dashboard with `docker compose -f docker-compose.dashboard.yml up`."
)
