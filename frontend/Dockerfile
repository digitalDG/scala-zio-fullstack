# ---- Build: type-check and bundle the app ------------------------------------------------------
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# ---- Serve: static files plus a reverse proxy to the API ---------------------------------------
FROM nginx:1.27-alpine

# The image renders /etc/nginx/templates/*.template into /etc/nginx/conf.d at startup.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html

# Defaults for Docker Compose; override them per environment (Railway sets PORT itself).
ENV PORT=80 \
    BACKEND_URL=http://backend:8080 \
    NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1

EXPOSE 80
