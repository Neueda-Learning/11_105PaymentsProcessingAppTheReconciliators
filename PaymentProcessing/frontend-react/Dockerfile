# ---------- Build stage ----------
FROM node:20-alpine AS build
ENV NODE_VERSION=20
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY index.html vite.config.js ./
COPY src ./src
RUN npm run build

# ---------- Runtime stage ----------
FROM nginx:1.27-alpine
WORKDIR /app
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8083
ENTRYPOINT ["nginx", "-g", "daemon off;"]
