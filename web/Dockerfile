# OPS-9: the unprivileged nginx image runs as user "nginx" (uid 101), not as root
FROM nginxinc/nginx-unprivileged:1.27-alpine
# The image fills ${...} in templates from environment variables at startup
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY public/ /usr/share/nginx/html/
EXPOSE 8080
