FROM java:8-alpine

ADD target/votemeal-0.0.1-SNAPSHOT-standalone.jar /votemeal/app.jar

EXPOSE 8080

CMD ["java", "-jar", "/votemeal/app.jar"]
