# ---- build stage: bundle the React app with esbuild ----
FROM node:22.12.0-alpine AS build
WORKDIR /app
COPY package.json ./
RUN npm install --no-audit --no-fund
COPY index.html ./
COPY src ./src
RUN npm run build

# ---- run stage: nginx serves the bundle and proxies the APIs (Section 6.1) ----
# OPS-9: the unprivileged nginx image runs as user "nginx" (uid 101), not as root
FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist/ /usr/share/nginx/html/
EXPOSE 8080
