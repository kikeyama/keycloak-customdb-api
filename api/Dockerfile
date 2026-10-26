# 先にホストで `mvn -DskipTests package` を実行して target/*.jar を作っておくこと。
# コンテナ内の Maven は社内ネットワークの TLS 検査で Maven Central に繋がらない(PKIX エラー)ため、
# ビルドはホストで行い、ここでは jar をコピーするだけにしている。
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/keycloak-auth0-api-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
