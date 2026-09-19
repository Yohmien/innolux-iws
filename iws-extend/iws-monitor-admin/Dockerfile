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

RUN mkdir -p /iws/monitor/logs

WORKDIR /iws/monitor

ENV LANG=C.UTF-8 LC_ALL=C.UTF-8 JAVA_OPTS=""

EXPOSE 18090

ADD ./target/iws-monitor-admin.jar ./app.jar

SHELL ["/bin/bash", "-c"]

ENTRYPOINT java -Djava.security.egd=file:/dev/./urandom \
           -XX:+HeapDumpOnOutOfMemoryError -XX:+UseZGC ${JAVA_OPTS} \
           -jar app.jar
