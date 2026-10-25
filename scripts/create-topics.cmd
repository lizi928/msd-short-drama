@echo off
setlocal

if "%JAVA_HOME%"=="" set "JAVA_HOME=C:\Users\jun\.jdks\ms-21.0.11"
set "KAFKA_HOME=D:\kafka_2.13-4.1.2"
set "BOOTSTRAP=192.168.33.129:9092"
set "KAFKA_LOG4J_OPTS=-Dlog4j2.configurationFile=file:///%KAFKA_HOME:\=/%/config/tools-log4j2.yaml"

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
  call "%KAFKA_HOME%\bin\windows\kafka-topics.bat" --bootstrap-server %BOOTSTRAP% --create --if-not-exists --topic %%T --partitions 3 --replication-factor 1
)

echo [INFO] topics:
call "%KAFKA_HOME%\bin\windows\kafka-topics.bat" --bootstrap-server %BOOTSTRAP% --list

endlocal
