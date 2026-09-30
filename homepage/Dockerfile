FROM node:24.11.0-alpine AS build
WORKDIR /app/homepage
RUN npm install --global "pnpm@12.6.0"
COPY homepage/package.json homepage/pnpm-lock.yaml homepage/pnpm-workspace.yaml ./
COPY packages/design-system /app/packages/design-system
RUN pnpm install --frozen-lockfile
COPY homepage/ ./
ARG PUBLIC_WORKSPACE_URL=http://127.0.0.1:3000
ENV PUBLIC_WORKSPACE_URL=${PUBLIC_WORKSPACE_URL}
RUN pnpm run build

FROM nginx:alpine AS runner
COPY homepage/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/homepage/dist /usr/share/nginx/html
EXPOSE 80
