@echo off
setlocal

if "%JAVA_HOME%"=="" set "JAVA_HOME=C:\Users\jun\.jdks\ms-21.0.11"
set "KAFKA_HOME=D:\kafka_2.13-4.1.2"
set "BOOTSTRAP=192.168.33.129:9092"
set "KAFKA_LOG4J_OPTS=-Dlog4j2.configurationFile=file:///%KAFKA_HOME:\=/%/config/tools-log4j2.yaml"
set "PARTITIONS=6"

for %%T in (
  msd.infra.smoke.v1
  msd.interaction.like.v1
  msd.interaction.favorite.v1
  msd.interaction.comment.v1
  msd.interaction.history.v1
  msd.drama.changed.v1
  msd.ai.review.request.v1
  msd.interaction.dlq.v1
) do (
  echo [INFO] create topic %%T
  call "%KAFKA_HOME%\bin\windows\kafka-topics.bat" --bootstrap-server %BOOTSTRAP% --create --if-not-exists --topic %%T --partitions %PARTITIONS% --replication-factor 1
)

rem 已存在的 topic 从 3 分区扩到 6；Kafka 不支持缩分区，重复执行无副作用
for %%T in (
  msd.infra.smoke.v1
  msd.interaction.like.v1
  msd.interaction.favorite.v1
  msd.interaction.comment.v1
  msd.interaction.history.v1
  msd.drama.changed.v1
  msd.ai.review.request.v1
  msd.interaction.dlq.v1
) do (
  echo [INFO] ensure partitions %%T = %PARTITIONS%
  call "%KAFKA_HOME%\bin\windows\kafka-topics.bat" --bootstrap-server %BOOTSTRAP% --alter --topic %%T --partitions %PARTITIONS%
)

echo [INFO] topics:
call "%KAFKA_HOME%\bin\windows\kafka-topics.bat" --bootstrap-server %BOOTSTRAP% --list

endlocal
