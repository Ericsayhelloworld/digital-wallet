FROM nginx:1.27-alpine
# The official image fills ${...} in templates from environment variables at startup
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY public/ /usr/share/nginx/html/
EXPOSE 8080
