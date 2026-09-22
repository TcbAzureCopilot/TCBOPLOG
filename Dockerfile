# =====================================================================
#  TCBOPLOG — 機房操作日誌 試用版 (Spring Boot + embedded H2 + auth bypass)
#
#    docker build -t tcboplog .
#    docker run -d --name tcboplog -p 8080:8080 -v tcboplog-data:/data tcboplog
#    → http://localhost:8080/
# =====================================================================

# ---------- stage 1: build both Maven projects ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
# core (WebSphere WAR) — installed to the local repo so the Boot module can overlay it
COPY pom.xml ./
COPY db ./db
COPY src ./src
RUN mvn -q -B -DskipTests install
# Spring Boot wrapper
COPY tcboplog ./tcboplog
RUN mvn -q -B -DskipTests -f tcboplog/pom.xml package

# ---------- stage 2: runtime ----------
FROM eclipse-temurin:17-jre-jammy
# TrueType Chinese font for PDF output (CFF .otf fonts render badly with OpenPDF); tzdata for the logical day
RUN apt-get update \
 && apt-get install -y --no-install-recommends fonts-wqy-zenhei tzdata curl \
 && rm -rf /var/lib/apt/lists/*
ENV TZ=Asia/Taipei \
    MACHINEROOM_CONFIG=/app/config/app.properties \
    JAVA_OPTS="-Xms128m -Xmx512m -Duser.timezone=Asia/Taipei -Dfile.encoding=UTF-8"
WORKDIR /app
COPY --from=build /src/tcboplog/target/tcboplog.war /app/tcboplog.war
COPY tcboplog/config/app.properties /app/config/app.properties
RUN mkdir -p /data /app/fonts && useradd -r -u 10001 tcboplog && chown -R tcboplog:tcboplog /app /data
USER tcboplog
VOLUME ["/data"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s \
  CMD curl -fs http://localhost:8080/login >/dev/null || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/tcboplog.war"]
