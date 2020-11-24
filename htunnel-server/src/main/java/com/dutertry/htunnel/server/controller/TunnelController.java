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
package com.dutertry.htunnel.server.controller;

import static com.dutertry.htunnel.common.Constants.HEADER_CONNECTION_ID;

import java.io.FileReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.PostConstruct;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.dutertry.htunnel.common.ConnectionConfig;
import com.dutertry.htunnel.common.ConnectionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author Nicolas Dutertry
 *
 */
@RestController
public class TunnelController {
    private static final Logger LOGGER = LoggerFactory.getLogger(TunnelController.class);
    
    private static final long READ_WAIT_TIME = 10000L;
    
    @Value("${public-key:}")
    private String publicKeyPath;
    
    private PublicKey publicKey;
    
    private final Map<String , ClientConnection> connections = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void init() throws IOException {
        if(StringUtils.isNotBlank(publicKeyPath)) {
            LOGGER.info("Using public key {} for connection certification", publicKeyPath);
            SubjectPublicKeyInfo subjectPublicKeyInfo;
            try(FileReader reader = new FileReader(publicKeyPath);
                    PEMParser pemParser = new PEMParser(reader)) {
                subjectPublicKeyInfo = SubjectPublicKeyInfo.getInstance(pemParser.readObject());
            }
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            publicKey = converter.getPublicKey(subjectPublicKeyInfo);
        }
    }
    
    @RequestMapping(value = "/hello", method = RequestMethod.GET)
    public String hello() throws IOException {
        return LocalDateTime.now().toString();
    }
    
    @RequestMapping(value = "/connect", method = RequestMethod.POST)
    public String connection(
            HttpServletRequest request,
            @RequestBody byte[] connectionRequestBytes) throws IOException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        
        byte[] decrypted = connectionRequestBytes;
        if(publicKey != null) {
            byte[] crypted = Base64.getDecoder().decode(connectionRequestBytes);
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, publicKey);
            decrypted = cipher.doFinal(crypted);
        }
        
        ObjectMapper mapper = new ObjectMapper();
        ConnectionRequest connectionRequest = mapper.readValue(decrypted, ConnectionRequest.class);
        
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime helloDateTime = LocalDateTime.parse(connectionRequest.getHelloResult());
        if(helloDateTime.until(now, ChronoUnit.SECONDS) > 300) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN); 
        }
        
        ConnectionConfig connectionConfig = connectionRequest.getConnectionConfig();
        String ipAddress = request.getRemoteAddr();
        String host = connectionConfig.getHost();
        int port = connectionConfig.getPort();
        LOGGER.info("New connection received from {} for target {}:{}",
                ipAddress, host, port);
        LOGGER.info("Buffer size is {}", connectionConfig.getBufferSize());
        LOGGER.info("Base64 encoding is {}", connectionConfig.isBase64Encoding());
        
        SocketChannel socketChannel = SocketChannel.open();
        SocketAddress socketAddr = new InetSocketAddress(host, port);
        socketChannel.connect(socketAddr);
        socketChannel.configureBlocking(false);
        
        String connectionId = UUID.randomUUID().toString();
        
        connections.put(connectionId, new ClientConnection(connectionId, ipAddress, connectionConfig, LocalDateTime.now(), socketChannel));
        
        return connectionId;
    }

    @RequestMapping(value = "/write", method = RequestMethod.POST)
    public void write(
            HttpServletRequest request,
            @RequestHeader(HEADER_CONNECTION_ID) String connectionId,
            @RequestBody byte[] body) throws IOException {
        
        String ipAddress = request.getRemoteAddr();
        
        LOGGER.debug("New write request from {} for ID {} with body length {}", ipAddress, connectionId, body.length);
        
        ClientConnection connection = getConnection(ipAddress, connectionId);
        SocketChannel socketChannel = connection.getSocketChannel();
        
        byte[] bytes = body;
        if(connection.getConnectionConfig().isBase64Encoding()) {
            bytes = Base64.getDecoder().decode(body);
        }
        
        if(bytes.length > 0) {
            ByteBuffer bb = ByteBuffer.wrap(bytes);
            while(bb.hasRemaining()) {
                socketChannel.write(bb);
            }
        }
    }
    
    @RequestMapping(value = "/read", method = RequestMethod.GET)
    public byte[] read(
            HttpServletRequest request,
            @RequestHeader(HEADER_CONNECTION_ID) String connectionId) throws IOException {
        
        String ipAddress = request.getRemoteAddr();
        
        LOGGER.debug("New read request from {} for ID {}", ipAddress, connectionId);
        
        ClientConnection connection = getConnection(ipAddress, connectionId);
        SocketChannel socketChannel = connection.getSocketChannel();
        
        ByteBuffer bb = connection.getReadBuffer();
        bb.clear();
        
        long startTime  = System.currentTimeMillis();
        while(true) {
            int read;
            try {
                read = socketChannel.read(bb);
            } catch(ClosedChannelException e) {
                read = -1;
            }
            
            if(!bb.hasRemaining() || read <= 0) {
                if(bb.position() > 0) {
                    bb.flip();
                    
                    ByteBuffer resultBuffer = bb;
                    if(connection.getConnectionConfig().isBase64Encoding()) {
                        resultBuffer = Base64.getEncoder().encode(bb);
                    }
                    
                    byte[] bytes = new byte[resultBuffer.limit()];
                    resultBuffer.get(bytes);
                    
                    return bytes;
                } else {
                    if(read == -1) {
                        throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "EOF reached");
                    }
                    
                    long now = System.currentTimeMillis();
                    if(now-startTime >= READ_WAIT_TIME) {
                        return new byte[0];
                    }
                }
            }
        }
    }
    
    @RequestMapping(value = "/close", method = RequestMethod.GET)
    public void close(
            HttpServletRequest request,
            @RequestHeader(HEADER_CONNECTION_ID) String connectionId) throws IOException {
        
        String ipAddress = request.getRemoteAddr();
        
        LOGGER.info("New close request from {} for ID {}", ipAddress, connectionId);
        
        ClientConnection connection = getConnection(ipAddress, connectionId);
        SocketChannel socketChannel = connection.getSocketChannel();
        
        socketChannel.close();
        
        connections.remove(connectionId);
    }
    
    @RequestMapping(value = "/clean", method = RequestMethod.GET)
    public String clean() {
        LOGGER.info("Cleaning connections");
        int closed = 0;
        int error = 0;
        Iterator<Map.Entry<String, ClientConnection>> it = connections.entrySet().iterator();
        while(it.hasNext()) {
            Map.Entry<String, ClientConnection> entry = it.next();
            closed++;
            try {
                entry.getValue().getSocketChannel().close();
            } catch(Exception e) {
                LOGGER.error("Error while cleaning connections", e);
                error++;
            }
            it.remove();
        }
        
        return closed + " connection(s) closed (" + error + " with error)";

    }

    @RequestMapping(value = "/", method = RequestMethod.GET)
    public String about(final HttpServletRequest request, final HttpServletResponse response)
            throws URISyntaxException, IOException {
        return "htunnel";
    }
    
    private ClientConnection getConnection(String ipAddress, String connectionId) {
        ClientConnection connection = connections.get(connectionId);
        if(connection == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unable to find connection");
        }
        if(!StringUtils.equals(ipAddress, connection.getIpAddress())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        
        return connection;
    }
}
