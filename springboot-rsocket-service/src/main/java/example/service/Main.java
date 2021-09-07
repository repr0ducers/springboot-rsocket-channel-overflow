package example.service;

import io.netty.util.internal.shaded.org.jctools.queues.SpscLinkedQueue;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.rsocket.messaging.RSocketStrategiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.codec.cbor.Jackson2CborDecoder;
import org.springframework.http.codec.cbor.Jackson2CborEncoder;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@SpringBootApplication
public class Main {

    public static void main(String... args) {
        SpringApplication.run(Main.class, args);
    }

    /*CBOR message encoding*/
    @Bean
    public RSocketStrategiesCustomizer rSocketStrategiesCustomizer() {
        return strategies -> strategies
                .decoder(new Jackson2CborDecoder())
                .encoder(new Jackson2CborEncoder());
    }

    @Controller
    public static class Service {
        private static final Logger logger = LoggerFactory.getLogger(Service.class);

        /*RoutingMetadata of RSocket*/
        @MessageMapping("slow-channel")
        public Mono<Response> slowChannel(Publisher<Request> request) {
            Response responseMessage = new Response();
            responseMessage.setMessage("slow-channel");

            final Queue<Request> workQueue = new SpscLinkedQueue<>();
            MonoProcessor<Response> responseStream = MonoProcessor.create();
            Flux.from(request).subscribe(new Subscriber<Request>() {
                private final AtomicLong totalReceived = new AtomicLong();
                private Disposable disposable;

                @Override
                public void onSubscribe(Subscription s) {
                    int requestN = 5;
                    s.request(requestN);
                    /*imitate slow request processing: 5 messages per second*/
                    disposable = Flux.interval(Duration.ofMillis(200))
                            .subscribe(new Consumer<Long>() {
                                long toProcess = requestN;
                                long totalRequested = requestN;

                                @Override
                                public void accept(Long v) {
                                    /*request "requestN" new messages as soon as toProcess window is exhausted*/
                                    Request processed = workQueue.poll();
                                    if (processed != null) {
                                        if (--toProcess == 0) {
                                            toProcess += requestN;
                                            totalRequested += requestN;
                                            s.request(requestN);
                                        }
                                    }
                                    if (v % 5 == 0) {
                                        logger.info("payload total requested: {}, total received: {}", totalRequested, totalReceived.get());
                                    }
                                    if (v % 25 == 0) {
                                        logger.info("work queue size: " + workQueue.size());
                                    }
                                }
                            });
                }

                @Override
                public void onNext(Request request) {
                    workQueue.offer(request);
                    totalReceived.incrementAndGet();
                }

                @Override
                public void onError(Throwable t) {
                    disposable.dispose();
                    responseStream.onError(t);
                }

                @Override
                public void onComplete() {
                    disposable.dispose();
                    responseStream.onNext(responseMessage);
                    responseStream.onComplete();
                }
            });
            return responseStream;
        }

        static class Response {
            private String message;

            public String getMessage() {
                return message;
            }

            public void setMessage(String message) {
                this.message = message;
            }
        }

        static class Request {
            private String message;

            public String getMessage() {
                return message;
            }

            public void setMessage(String message) {
                this.message = message;
            }
        }
    }
}
