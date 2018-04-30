# Dutch Auction Web App

Demo app implementing a dutch auction.
* Updates to every auction state are sent over SSE (Server Sent Events).
* All security aspects have been neglected
* Due to the Author's inexperience with the node ecosystem, the app has been implemented in ClojureScript

## How to build with Docker

There is a Dockerfile for simple deployment
The page is available at [http://localhost:3000]("http://localhost:3000")

```
$ docker build -t dutch .
$ docker run -p 3000:3000 dutch:latest
```

I have not figured out how to make node honor `ctrl-c` in a docker image, 
therefore i apologize in advance for lost terminal sessions :-)

## How to build manually

Node and npm need to be available in `$PATH`

```
$ brew install leiningen
$ lein cljsbuild once server
$ lein cljsbuild once client
$ node target/cljsbuild-main.js
```  

Happy bidding!





