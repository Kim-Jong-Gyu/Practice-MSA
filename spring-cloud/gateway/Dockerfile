FROM eclipse-temurin:21-jre

EXPOSE 8443

COPY build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "/app.jar"]