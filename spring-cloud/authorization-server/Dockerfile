FROM eclipse-temurin:21-jre

EXPOSE 9999

COPY build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "/app.jar"]
