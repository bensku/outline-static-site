FROM gradle:7.6-jdk17-alpine as builder

RUN mkdir /work
COPY . /work
WORKDIR /work

RUN gradle openApiGenerate --no-daemon && gradle shadowJar --no-daemon

FROM eclipse-temurin:20-alpine

COPY --from=builder /work/build/libs/work-all.jar /opt/outline-static-site.jar
CMD [ "java", "-jar", "/opt/outline-static-site.jar" ]