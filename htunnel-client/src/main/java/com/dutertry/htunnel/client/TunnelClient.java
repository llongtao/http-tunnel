/*
 * htunnel - A simple HTTP tunnel 
 * https://github.com/nicolas-dutertry/htunnel
 * 
 * Written by Nicolas Dutertry.
 * 
 * This file is provided under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */
package com.dutertry.htunnel.client;

import static com.dutertry.htunnel.common.Constants.HEADER_CONNECTION_ID;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
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
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dutertry.htunnel.common.ConnectionConfig;
import com.dutertry.htunnel.common.ConnectionRequest;
import com.dutertry.htunnel.common.crypto.CryptoUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author Nicolas Dutertry
 *
 */
public class TunnelClient implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(TunnelClient.class);
    
    private final SocketChannel socketChannel;
    private final String host;
    private final int port;
    private final String tunnel;
    private final String proxy;
    private final int bufferSize;
    private final boolean base64Encoding;
    private final PrivateKey privateKey;
    
    private String connectionId;
    
    public TunnelClient(SocketChannel socketChannel, String host, int port, String tunnel, String proxy, int bufferSize, boolean base64Encoding, PrivateKey privateKey) {
        this.socketChannel = socketChannel;
        this.host = host;
        this.port = port;
        this.tunnel = tunnel;
        this.proxy = proxy;
        this.bufferSize = bufferSize;
        this.base64Encoding = base64Encoding;
        this.privateKey = privateKey;
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
            // Hello
            URI helloUri = new URIBuilder(tunnel)
                    .setPath("/hello")
                    .build();
            String helloResult;
            try(CloseableHttpResponse response = httpclient.execute(new HttpGet(helloUri))) {
                if(response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                    LOGGER.error("Error while connecting tunnel: {}", response.getStatusLine());
                    return;
                }
                helloResult = EntityUtils.toString(response.getEntity());
            }

            if(privateKey != null) {
                helloResult = new String(CryptoUtils.encryptRSA(helloResult.getBytes(StandardCharsets.UTF_8), privateKey));
            }
            
            // Connect
            URI connectUri = new URIBuilder(tunnel)
                    .setPath("/connect")
                    .build();
            
            ConnectionConfig connectionConfig = new ConnectionConfig();
            connectionConfig.setHost(host);
            connectionConfig.setPort(port);
            connectionConfig.setBufferSize(bufferSize);
            connectionConfig.setBase64Encoding(base64Encoding);
            
            ConnectionRequest connectionRequest = new ConnectionRequest();
            connectionRequest.setHelloResult(helloResult);
            connectionRequest.setConnectionConfig(connectionConfig);            
            
            ObjectMapper mapper = new ObjectMapper();
            byte[] sendBytes = mapper.writeValueAsBytes(connectionRequest);

            
            HttpPost httppost = new HttpPost(connectUri);
            httppost.setEntity(new ByteArrayEntity(sendBytes));
            
            try(CloseableHttpResponse response = httpclient.execute(httppost)) {
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
        } finally {
            if(connectionId == null) {
                try {
                    socketChannel.close();
                } catch (IOException e) {
                }
            }
        }
            
        Thread writeThread = new Thread(this::writeLoop);
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
                    int status = response.getStatusLine().getStatusCode();
                    if(status == HttpStatus.SC_GONE) {
                        LOGGER.info("Connection closed by server");
                        break;
                    }
                    
                    if(status != HttpStatus.SC_OK) {
                        LOGGER.error("Error while reading: {}", response.getStatusLine());
                        break;
                    }
                    
                    byte[] bytes = EntityUtils.toByteArray(response.getEntity());
                    if(bytes.length > 0) {
                        if(base64Encoding) {
                            bytes = Base64.getDecoder().decode(bytes);
                        }
                        
                        LOGGER.debug("{} byte(s) received", bytes.length); 
                        
                        ByteBuffer bb = ByteBuffer.wrap(bytes);
                        while(bb.hasRemaining()) {
                            socketChannel.write(bb);
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
            
            ByteBuffer bb = ByteBuffer.allocate(bufferSize);
            
            while(!Thread.currentThread().isInterrupted()) {
                int read = socketChannel.read(bb);
                
                if(!bb.hasRemaining() || read <= 0) {
                    if(bb.position() > 0) {
                        URI writeUri = new URIBuilder(tunnel)
                                .setPath("/write")
                                .build();
                        
                        HttpPost httppost = new HttpPost(writeUri);
                        httppost.addHeader(HEADER_CONNECTION_ID, connectionId);
                        
                        bb.flip();
                        
                        LOGGER.debug("{} byte(s) to send", bb.limit());
                        
                        if(base64Encoding) {
                            ByteBuffer encodedBuffer = Base64.getEncoder().encode(bb);
                            String body = StandardCharsets.UTF_8.decode(encodedBuffer).toString();
                            httppost.setEntity(new StringEntity(body, "UTF-8"));
                        } else {
                            httppost.setEntity(new ByteArrayEntity(bb.array(), 0, bb.limit()));
                        }
                        
                        bb.clear();
                        
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
