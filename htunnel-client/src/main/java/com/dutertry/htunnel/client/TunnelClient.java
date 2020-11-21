/*
 * © 1996-2014 Sopra HR Software. All rights reserved
 */
package com.dutertry.htunnel.client;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHost;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author ndutertry
 *
 */
public class TunnelClient implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(TunnelClient.class);
    
    private static final String HEADER_CONNECTION_ID = "X-HTUNNEL-ID";
    private static final int BUFFER_SIZE = 1024;
    
    private final SocketChannel socketChannel;
    private final String host;
    private final int port;
    private final String tunnel;
    private final String proxy;
    
    private String connectionId;
    
    public TunnelClient(SocketChannel socketChannel, String host, int port, String tunnel, String proxy) {
        this.socketChannel = socketChannel;
        this.host = host;
        this.port = port;
        this.tunnel = tunnel;
        this.proxy = proxy;
    }
    
    public CloseableHttpClient createHttpCLient() throws URISyntaxException {
        HttpClientBuilder builder = HttpClients.custom();
        if(StringUtils.isNotBlank(proxy)) {
            URI proxyUri = new URI(proxy);
            
            HttpHost proxy = new HttpHost(proxyUri.getHost(), proxyUri.getPort(), proxyUri.getScheme());
            DefaultProxyRoutePlanner routePlanner = new DefaultProxyRoutePlanner(proxy);
            builder.setRoutePlanner(routePlanner);

            // Proxy authentication
            String userInfo = proxyUri.getUserInfo();
            if(StringUtils.isNotBlank(userInfo)) {
                String user = StringUtils.substringBefore(userInfo, ":");
                String password = StringUtils.substringAfter(userInfo, ":");
                CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(
                    new AuthScope(proxyUri.getHost(), proxyUri.getPort()), 
                    new UsernamePasswordCredentials(user, password));
                builder.setDefaultCredentialsProvider(credentialsProvider);
            }
        }
        return  builder.build();
    }

    @Override
    public void run() {
        LOGGER.info("Connecting to tunnel {}", tunnel);
        try(CloseableHttpClient httpclient = createHttpCLient()) {
            URI connectUri = new URIBuilder(tunnel)
                    .setPath("/connect")
                    .setParameter("host", host)
                    .setParameter("port", Integer.toString(port))
                    .build();
            try(CloseableHttpResponse response = httpclient.execute(new HttpGet(connectUri))) {
                if(response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                    LOGGER.error("Error while connecting tunnel: {}", response.getStatusLine());
                    return;
                }
                connectionId = EntityUtils.toString(response.getEntity());
            }
            
            LOGGER.info("Connection established with id {}", connectionId);
        } catch(Exception e) {
            LOGGER.error("Error while connecting to tunnel", e);
            return;
        }
            
        Thread writeThread = new Thread(() -> this.writeLoop());
        writeThread.setDaemon(true);
        writeThread.start();
        
        readLoop();
    }
    
    private void readLoop() {
        try(CloseableHttpClient httpclient = createHttpCLient()) {
            URI readUri = new URIBuilder(tunnel)
                    .setPath("/read")
                    .build();
            while(!Thread.currentThread().isInterrupted()) {
                HttpGet httpget = new HttpGet(readUri);
                httpget.addHeader(HEADER_CONNECTION_ID, connectionId);
                try(CloseableHttpResponse response = httpclient.execute(httpget)) {
                    if(response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                        LOGGER.error("Error while reading: {}", response.getStatusLine());
                        break;
                    }
                    
                    String body = EntityUtils.toString(response.getEntity());
                    if(StringUtils.isNotEmpty(body)) {
                        byte[] bytes = Base64.getDecoder().decode(body);
                        if(bytes.length > 0) {
                            ByteBuffer bb = ByteBuffer.wrap(bytes);
                            while(bb.hasRemaining()) {
                                socketChannel.write(bb);
                            }
                        }
                    }
                }
            }
        } catch(Exception e) {
            LOGGER.error("Error in read loop", e);
        }
        
        try {
            socketChannel.close();
        } catch (IOException e) {
        }
        LOGGER.info("Read loop terminated for {}", connectionId);
    }
    
    private void writeLoop() {
        try(CloseableHttpClient httpclient = createHttpCLient()) {
            
            ByteBuffer bb = ByteBuffer.allocate(BUFFER_SIZE);
            
            while(!Thread.currentThread().isInterrupted()) {
                int read = socketChannel.read(bb);
                
                if(!bb.hasRemaining() || read <= 0) {
                    if(bb.position() > 0) {
                        bb.flip();
                        ByteBuffer encodedBuffer = Base64.getEncoder().encode(bb);
                        String body = StandardCharsets.UTF_8.decode(encodedBuffer).toString();
                        bb.clear();
                        
                        URI writeUri = new URIBuilder(tunnel)
                                .setPath("/write")
                                .build();
                        
                        HttpPost httppost = new HttpPost(writeUri);
                        httppost.addHeader(HEADER_CONNECTION_ID, connectionId);
                        httppost.setEntity(new StringEntity(body, "UTF-8"));
                        
                        try(CloseableHttpResponse response = httpclient.execute(httppost)) {
                            if(response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                                LOGGER.error("Error while writing: {}", response.getStatusLine());
                                return;
                            }
                            
                            EntityUtils.consume(response.getEntity());
                        }
                    }
                }
                
                if(read == -1) {                    
                    break;
                }
            }
            
        } catch(Exception e) {
            LOGGER.error("Error in write loop", e);
        }
        
        try(CloseableHttpClient httpclient = createHttpCLient()) {
            URI closeUri = new URIBuilder(tunnel)
                    .setPath("/close")
                    .build();
            
            HttpGet httpget = new HttpGet(closeUri);
            httpget.addHeader(HEADER_CONNECTION_ID, connectionId);
            try(CloseableHttpResponse response = httpclient.execute(httpget)) {
                EntityUtils.consume(response.getEntity());
            }
        } catch(Exception e) {
            LOGGER.error("Error while closing connection", e);
        }
        
        try {
            socketChannel.close();
        } catch (IOException e) {
        }
        
        LOGGER.info("Write loop terminated for {}", connectionId);
        
    }

}
