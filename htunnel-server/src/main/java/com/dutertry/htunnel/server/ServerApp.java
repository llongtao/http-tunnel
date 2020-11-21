/*
 * © 1996-2014 Sopra HR Software. All rights reserved
 */
package com.dutertry.htunnel.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.ApplicationPidFileWriter;

/**
 *
 * @author ndutertry
 */
@SpringBootApplication
public class ServerApp {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ServerApp.class);
        app.addListeners(new ApplicationPidFileWriter());
        app.run(args);
    }
}
