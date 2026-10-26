# Imagen de pruebas: el servicio y Java corren como mysql, sin root en ejecución.
FROM eclipse-temurin:25-jdk-jammy AS java
FROM mariadb:11.4.13
COPY --from=java /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"
USER mysql
WORKDIR /work
ENTRYPOINT []
CMD ["bash", "./gradlew", "integrationTest", "--no-daemon", "--console=plain"]
