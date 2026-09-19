# @Date         : 2026-09-19
# @Author       : YONGJIE.PENG
# @Email        : YONGJIE.PENG@INNOLUX.COM
# @Job          : INNOLUX IWS 标准化重构
# @Module       : IWS 智能仓储管理系统
# @Version      : V1.0
# @Description  : 统一前后端命名、基础架构、数据库脚本与智能仓储业务入口；
# @ModifyRecord : V1.0 新建；
# 贝尔实验室 Spring 官方推荐镜像 JDK下载地址 https://bell-sw.com/pages/downloads/
FROM bellsoft/liberica-openjdk-rocky:21.0.12-cds
# FROM bellsoft/liberica-openjdk-rocky:25.0.4-cds
# FROM findepi/graalvm:java21-native

LABEL maintainer="YONGJIE.PENG"

RUN mkdir -p /iws/server/logs \
    /iws/server/temp \
    /iws/skywalking/agent

WORKDIR /iws/server

ENV SERVER_PORT=18080 SNAIL_JOB_PORT=18280 SNAIL_AI_PORT=18380 LANG=C.UTF-8 LC_ALL=C.UTF-8 JAVA_OPTS=""

EXPOSE ${SERVER_PORT}
# 暴露 snail 客户端端口 用于定时任务调度中心通信
EXPOSE ${SNAIL_JOB_PORT}
EXPOSE ${SNAIL_AI_PORT}

ADD ./target/iws-admin.jar ./app.jar

SHELL ["/bin/bash", "-c"]

ENTRYPOINT java -Djava.security.egd=file:/dev/./urandom -Dserver.port=${SERVER_PORT} \
           -Dsnail-job.port=${SNAIL_JOB_PORT} \
           -Dsnail-ai.port=${SNAIL_AI_PORT} \
           # 应用名称 如果想区分集群节点监控 改成不同的名称即可
           #-Dskywalking.agent.service_name=iws-server \
           #-javaagent:/iws/skywalking/agent/skywalking-agent.jar \
           -XX:+HeapDumpOnOutOfMemoryError -XX:+UseZGC ${JAVA_OPTS} \
           -jar app.jar
