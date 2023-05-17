FROM openjdk:17-jdk-alpine
VOLUME /tmp
ENV JAVA_OPTS ""
COPY ./target/*.jar app.jar
ENTRYPOINT java ${JAVA_OPTS} -Djava.security.egd=file:/dev/./urandom -jar /app.jar