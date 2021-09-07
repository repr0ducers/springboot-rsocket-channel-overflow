## Spring RSocket/RSocket-java: server request-channel memory overflow 

### DESCRIPTION

RSocket/RSocket-java ([1.1.2](https://github.com/rsocket/rsocket-java/releases/tag/1.1.2) and below) integration 
from spring-boot (2.7.3 and below) is affected by potential denial-of-service with memory overflow using RSocket 
request-channel interaction.

RSocket as protocol relies on Reactive Streams for message flow control — for streaming interactions (request-channel in particular), 
responder is not allowed to push more messages than requested by peer with REQUEST_N frame.

RSocket/RSocket-java however does not enforce flow control - endpoints are allowed to produce more than their peers agreed to consume.

Malicious client may start new request-channel interaction, then send request payload frames at arbitrarily high rate 
regardless of demand (REQUEST_N) from server - which may end up with memory overflow If channel request messages 
are processed asynchronously, or if processing is CPU expensive.

Eventually fixed in RSocket/Rocket-java [1.1.3](https://github.com/rsocket/rsocket-java/pull/1067). 

Initially reported on springframework [issue](https://github.com/spring-projects/spring-framework/issues/27462) tracker
whose members are in charge of the project.

### PREREQUISITES

jdk 8+

### SETUP

Spring-boot 2.7.3 based application having `RSocket-java` service, started with 1GB memory limit: -Xms1024m, -Xmx1024m 
(`springboot-rsocket-service` module).

RSocket-java service implements request-channel interaction by modelling service with CPU expensive message processing.
Channel request inbound messages are enqueued on worker queue, then processed at rate of 5 msgs/sec. Service channel request 
demand starts with REQUEST_N=5, then refilled with new REQUEST_N=5 once messages for previous demand are processed. 

Malicious RSocket client (small subset of protocol, sufficient for vulnerability demonstration) is implemented with Netty 
(`channel-overflow-client` module).

It establishes RSocket connection, sends single request-channel with payload of 1600 bytes to server, then sends 
 request channel PAYLOAD frame 5000 times every 100 millis, regardless of REQUEST_N received from server. 

### RUNNING

Build server, client binaries `./gradlew clean build installDist`

Run server `./springboot_rsocket_service.sh` 

Run client `./overflow_client.sh` 

Eventually (10 - 15 seconds on a modern host, jdk11) `springboot-rsocket-service` reports `OutOfMemoryError`:

```
Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread "parallel-3"
```