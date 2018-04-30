FROM clojure:lein-2.8.1-alpine

EXPOSE 3000

RUN apk add --no-cache --update nodejs nodejs-npm

WORKDIR /app

COPY . /app

RUN npm install node-pre-gyp

RUN lein deps
RUN lein cljsbuild once client
RUN lein cljsbuild once server

CMD node target/cljsbuild-main.js