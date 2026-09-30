FROM node:24.11.0-alpine AS pnpm-base
WORKDIR /app/web
COPY web/package.json ./
RUN npm install --global "pnpm@$(node -p 'require("./package.json").packageManager.slice(5)')"

FROM pnpm-base AS dependencies
COPY web/pnpm-lock.yaml web/pnpm-workspace.yaml ./
COPY packages/design-system /app/packages/design-system
RUN pnpm install --frozen-lockfile

FROM pnpm-base AS builder
ENV NEXT_TELEMETRY_DISABLED=1
COPY --from=dependencies /app/web/node_modules ./node_modules
COPY --from=dependencies /app/packages/design-system /app/packages/design-system
COPY web/ ./
RUN pnpm run build

FROM node:24.11.0-alpine AS runner
WORKDIR /app/web
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 HOSTNAME=0.0.0.0 PORT=3000
COPY --from=builder /app/web/public ./public
COPY --from=builder /app/web/.next/standalone/web ./
COPY --from=builder /app/web/.next/static ./.next/static
EXPOSE 3000
CMD ["node", "server.js"]
