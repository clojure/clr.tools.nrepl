# clr.tools.nrepl #

A port of [babashka/babashka.nrepl](https://github.com/babashka/babashka.nrepl) library to ClojureCLR.

A shoutout to Michiel Borkent (@borkdude) for writing the original and for assistance in getting this port up and running.

There is a work-in-progress port of [nrepl/nrepl](https://github.com/nrepl/nrepl). That is in down in subdirectory `partial-nrepl-nrepl-port`.  Go for it.

# Status

We are in alpha for the first release.  The original test suite passes.  We are starting work to test against nREPL clients (think of Calva, CIDER).

# Usage

The original `babashka.nrepl` was build to to run under SCI. (See  [https://github.com/babashka/sci](https://github.com/babashka/sci).) This port has nothing to do with SCI.   
Otherwise the usage is similar to the original.  We reproduce those notes here with appropriate modifications.

The original version needed a SCI context passed in to `start-server!`.   
We have maintained that parameter for now -- we will be passing in an empty map -- until we can assess possible need for it in our context.  
This aspect of the interface may change as we continue with the alpha development.

## Starting a server

To start an nREPL server, call `clojure.tools.nrepl/start-server!`.  The call takes one optional argumen, a server options map.

```
(clojure.tools.nrepl/start-server! {:host "127.0.0.1" :port 12345})
```

Option keys include:

- `:debug` -- if set to `true`, the nREPL server will print to standard output all the messages it is receiving over the nREPL channel.
- `:debug-send` -- if set to `true`, the server will also print the messages it is sending
- `:quiet` -- if set to `true` the nREPL server will not print out the message "starting nREPL server at ..." when starting.  
Note that some clients (CIDER?) require this message in order pick up information such as the port number, or so I've heard.
If not specified, `:quiet` defatuls to `false`, and the message will be printed.
- `:port` -- the port number.  If not specified, defaults to `1667`.
- `:host` -- the host IP address or DNS name.   If not specified, it defaults to `0.0.0.0`. (Bind to every interface.)
- `:xform` -- if not specified, defatuls to `clojure.core.nrepl.middleware/default-xform`.  
See the [babashka.nrepl middleware docs](https://github.com/babashka/babashka.nrepl/blob/master/doc/middleware.md) for more information.

If no options hashmap is specifed at all, all the defaults will be used.  Thus you can start the nREPL server minimally with

```
(clojure.tools.nrepl/start-server!)
```

## Stopping a server

Pass the result returned from `start-server!` to `stop-server!`:

```
(def server (clojure.tools.nrepl/start-server!))
....

(clojure.tools.nrepl/stop-server! server)
```


## Parsing an nREPL options string

Use `clojure.tools.nrepl/parse-opt` to parse a `hostname:port` string into a map to pass to `start-server!`:

```
(clojure.tools.nrepl/start-server! {} (clojure.tools.nrepl/parse-opt "localhost:12345"))
```

## Middleware

The nREPL middleware is customizable.
Also this is untested.
We will be following the [babashka.nrepl middleware docs](https://github.com/babashka/babashka.nrepl/blob/master/doc/middleware.md).  

There is a rumor that the middleware design may change in the future.



# Releases


[clj](https://clojure.org/guides/getting_started) dependency information:
```clojure
io.github.clojure/clr.tools.nrepl {:git/tag "v0.1.2-alpha2" :git/sha "a58009f"}
```

```
PM> Install-Package clojure.tools.nrepl -Version 0.1.2-alpha2
```

Leiningen/Clojars reference:

```
[org.clojure.clr/tools.nrepl "0.1.0-alpha2]
```


# Copyright and License #

The babashka.nrepl code had the following:


> The project code is Copyright © 2019-2023 Michiel Borkent
>
> It is distributed under the Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)



