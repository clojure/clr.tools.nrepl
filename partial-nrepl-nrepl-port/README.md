# clr.tools.nrepl #

A port of [babashka/babashka.nrepl](https://github.com/babashka/babashka.nrepl) library to ClojureCLR.

# Releases

This is not not yet in a release.  The port is in progress.


# Status 

Note: if you work on this, please note that the root namespace is `cnrepl` , not `nrepl`.
This is due to a conflict with lein-clr -- it downloads some nrepl files and that messes up everything.
For now, I'm just kludging it this way.

Stuck in the middle of debugging.  Here is where things stand.

## Source code

| file | translated | loads | Comment |
|------|:----------:|:-----:|:--------|
| ack                           | Y | Y | |
| bencode                       | Y | Y | |
| cmdline                       | Y | Y | |
| config                        | Y | Y | |
| core                          | Y | Y | |
| helpers                       | Y | Y | |
| middleware                    | Y | Y | |
| misc                          | Y | Y | |
| server                        | Y | Y | |
| socket                        | Y | Y | |
| tls                           | N | - | Probably wont' bother with this for now |
| tls_client_proxy              | N | - | ditto |
| util/completion               | Y | Y | |
| util/lookup                   | Y | Y | |
| util/print                    | Y | Y | |
| middleware/caught             | Y | Y | |
| middleware/completion         | Y | Y | |
| middleware/dynamic_loader     | Y | Y | |
| middleware/interruptible_eval | Y | Y | |
| middleware/load_file          | Y | Y | |
| middleware/lookup             | Y | Y | |
| middleware/print              | Y | Y | |
| middleware/session            | Y | Y | |
| middleware/sideloader         | Y | Y | |

That's everything.

## Tests

| file | translated | loads | Status |
|------|:----------:|:-----:|:--------|
| bencode_test                 |  Y | Y | OK |
| cmdline_test                 |  N | - | -  |
| cmdline_tty_test             |  N | - | -  |
| core_test                    |  Y | Y | OK, but (1) |
| describe_test                |  Y | Y | OK, but (1)  |
| edn_test                     |  Y | Y | OK, but (1) |
| helpers_test                 |  N | - | -  |
| middleware_test              |  Y | Y | OK |
| misc_test                    |  Y | Y | OK |
| response_test                |  Y | Y | OK |
| sanity_test                  |  Y | Y | OK |
| tls_test                     |  N | - | -  |
| transport_test               |  Y | Y | OK |
| util/completion_test         |  Y | Y | OK |
| util/lookup_test             |  Y | Y | OK |
| middleware/completion_test   |  Y | Y | OK |
| middleware/dynamic_load_test |  N | - | -  |
| middleware/load_file_test    |  Y | Y | NO, see (2) |
| middleware/lookup_test       |  Y | Y | OK |
| middleware/print_test        |  Y | Y | OK |

Notes:

1. These tests all run to completion with no errors from the testing frameworks viewpoint,
but error messages are printed at the console.  There error messages are reported by `noisy-future`,
a macro that wraps a call to `future` and reports any exception thrown by the `future` thread.
The exceptions are being reported are from threads that 
while waiting for reads to complete have their sockets closed.
Because the tests run using both the bencode and edn transports, you get errors from each. 
From edn, the reported error is __ERROR: EOF while reading__.
From bencode, the reported error is __ERROR: Invalid netstring. Unexpected end of input.__.
If you were to just use `future` instead of `noisy-future`, you'd never know anything was up.

2.  This test prints out one each of the `noisy-future` errors, then hangs forever.


So close, so close ... .


# Copyright and License #

Original ClojureJVM code:

>Copyright © 2010-2020 Chas Emerick, Bozhidar Batsov and contributors.
>
>Licensed under the EPL. (See the file epl.html.)



