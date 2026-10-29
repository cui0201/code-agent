# syntax=docker/dockerfile:1
#
# 两阶段构建：构建阶段用完整 JDK，运行阶段只带 JRE。
#
# 注意：这是个多模块工程，构建时必须把全部模块一起拷进去，
# 少一个 pom 都会因为 reactor 缺模块而失败。
#
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# 先只拷贝 pom，利用 Docker 层缓存：源码改动时不会重新下载依赖
COPY pom.xml .
COPY code-agent-core/pom.xml code-agent-core/
COPY code-agent-indexer/pom.xml code-agent-indexer/
COPY code-agent-retrieval/pom.xml code-agent-retrieval/
COPY code-agent-agent/pom.xml code-agent-agent/
COPY code-agent-web/pom.xml code-agent-web/
COPY code-agent-eval/pom.xml code-agent-eval/
RUN mvn -B -q -DskipTests dependency:go-offline

COPY . .
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

# 以非 root 用户运行
RUN useradd -ms /bin/bash codeagent
COPY --from=build /workspace/code-agent-web/target/code-agent-web-*.jar /app/code-agent.jar
RUN mkdir -p /app/.codeagent && chown -R codeagent:codeagent /app
USER codeagent

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080

# 默认以服务模式启动；索引目录与存储类型由 compose / 环境变量传入
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/code-agent.jar"]
