# Imagen de pruebas: el servicio y Java corren como mysql, sin root en ejecución.
FROM eclipse-temurin:25-jdk-jammy AS java
FROM mariadb:11.4.13
COPY --from=java /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"
RUN mkdir -p /work && chown mysql:mysql /work
WORKDIR /work
COPY --chown=mysql:mysql gradlew build.gradle settings.gradle ./
COPY --chown=mysql:mysql gradle/ ./gradle/
COPY --chown=mysql:mysql common/ ./common/
COPY --chown=mysql:mysql proxy/ ./proxy/
COPY --chown=mysql:mysql client/ ./client/
USER mysql
ENTRYPOINT []
CMD ["bash", "./gradlew", "integrationTest", "--no-daemon", "--console=plain", "--rerun-tasks"]
