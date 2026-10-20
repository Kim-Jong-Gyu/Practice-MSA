FROM eclipse-temurin:21-jre

EXPOSE 8761

COPY build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "/app.jar"]