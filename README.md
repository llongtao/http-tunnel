# htunnel

A simple HTTP Tunnel written in Java to encapsulate any TCP/IP connection and pass through HTTP proxies.

It is typically used to perform SSH connections behind a corporate HTTP proxy that does not accept CONNECT method.

It consists of a client module (htunnel-client) to be installed in the private network behind the HTTP proxy and a server module (htunnel-server) to be installed anywere on the Internet.

     ----------------------------------------------------------------   -----------------------------------------------
    | Corporate Network                                              | | Internet                                      | 
    |                                                                | |                                               |
    |  --------------------------------------------                  | |                                               |
    | | Employee PC                                |                 | |                                               |
    | |                                            |                                                                   |
    | |  ------------   TCP/IP   ----------------  | HTTP   -------  HTTP   ----------------   TCP/IP   ------------   |
    | | | SSH Client |--------->| htunnel-client |-------->| Proxy |------>| htunnel-server |--------->| SSH Server |  |
    | |  ------------            ----------------  |        -------         ----------------            ------------   |
    | |                                            |                 | |                                               |
    |  --------------------------------------------                  | |                                               |
    |                                                                | |                                               |
     ----------------------------------------------------------------   -----------------------------------------------

## Build

The source code is written in Java and can be built with Apache Maven. Perform the following command in the root directory:

    mvn clean install

This will produce 2 jar files:
* htunnel-client/target/htunnel-client-_version_.jar
* htunnel-server/target/htunnel-server-_version_.jar

## Usage

### htunnel-server

Run htunnel-server on any machine in the Internet:

    java -jar htunnel-server-version.jar

It will start a HTTP server on port 8080.

You can change port number with option --server.port:

    java -jar htunnel-server-version.jar --server.port=80

### htunnel-client

Run htunnel-client on any machine in the corporate network:

    java -jar htunnel-client-version.jar --target=sshhost:sshport --tunnel=http://tunnelhost:8080/ --proxy=http://proxyhost:proxyport/

It will start the client daemon on port 3000. Any communication made with htunnel client will be transferred to the specified target.

You can change port number with option --port:

    java -jar htunnel-client-version.jar --port=4000 --target=sshhost:sshport --tunnel=http://tunnelhost:8080/ --proxy=http://proxyhost:proxyport/

If the proxy requires authentication you can specify a user and password in the proxy url: `--proxy=http://username:pasword@proxyhost:proxyport/`

Finally perform SSH connection:

    ssh -p 3000 localhost
