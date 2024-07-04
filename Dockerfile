FROM openjdk:17-jdk-alpine
VOLUME /tmp
ENV JAVA_OPTS ""
COPY ./htunnel-server/target/*.jar app.jar
ENTRYPOINT java ${JAVA_OPTS} -Djava.security.egd=file:/dev/./urandom -jar /app.jar