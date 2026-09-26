# 构建阶段编译 TypeScript；运行镜像只携带生产依赖、JavaScript 与迁移文件。
FROM node:24-bookworm-slim AS build
WORKDIR /app
COPY Server/package.json Server/package-lock.json ./
RUN npm ci
COPY Server/tsconfig*.json ./
COPY Server/src ./src
COPY Server/scripts ./scripts
RUN npm run build

FROM node:24-bookworm-slim AS runtime
ENV NODE_ENV=production HOST=0.0.0.0 PORT=8787
WORKDIR /app
COPY Server/package.json Server/package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY --from=build /app/dist ./dist
COPY Server/migrations ./migrations
USER node
EXPOSE 8787
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 CMD node -e "fetch('http://127.0.0.1:8787/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "dist/src/server.js"]
