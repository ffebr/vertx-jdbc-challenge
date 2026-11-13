FROM eclipse-temurin:17-jre-jammy@sha256:aa6381384f8db99762fa7d0d3c2b5de58874e5f12c96df4c13a1455376ae0ed1

ARG VARIANT=jdbc
RUN test "$VARIANT" = jdbc || test "$VARIANT" = native

WORKDIR /opt/conduit
COPY ${VARIANT}/target/stage/lib/ /opt/conduit/lib/
RUN chmod -R a+rX /opt/conduit/lib

ENV HOST=0.0.0.0 \
    PORT=3000
USER 10001:10001
EXPOSE 3000
ENTRYPOINT ["java", "-Xmx2g", "-Duser.timezone=UTC", "-cp", "/opt/conduit/lib/*", "conduit.Main"]
