FROM node:20-alpine

WORKDIR /usr/src/app

# Install production dependencies only
COPY package*.json ./
RUN npm install --omit=dev && npm cache clean --force

# Copy application source
COPY server.js ./

ENV NODE_ENV=production
ENV PORT=8080

# Run as non-root node user for container security on Cloud Run
USER node

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/health || exit 1

CMD ["node", "server.js"]
