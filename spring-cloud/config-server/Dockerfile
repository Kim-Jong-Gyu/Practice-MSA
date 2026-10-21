FROM eclipse-temurin:21-jre

EXPOSE 8888

COPY build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "/app.jar"]