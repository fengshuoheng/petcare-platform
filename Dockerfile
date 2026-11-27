# 构建阶段
FROM maven:3.9-eclipse-temurin-11 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# 运行阶段
FROM eclipse-temurin:11-jre
WORKDIR /app
ENV TZ=Asia/Shanghai
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
