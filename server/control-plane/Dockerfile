FROM node:22-alpine

# ffmpeg is used to grab a still frame from the live MediaMTX stream (snapshot).
RUN apk add --no-cache ffmpeg

WORKDIR /app

# Install deps first for layer caching
COPY package.json ./
RUN npm install

# Build
COPY tsconfig.json ./
COPY src ./src
RUN npm run build

EXPOSE 8080
CMD ["node", "dist/index.js"]
