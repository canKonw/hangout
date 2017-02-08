package com.ctrip.ops.sysdev.inputs;

import com.ctrip.ops.sysdev.baseplugin.BaseInput;
import kafka.consumer.ConsumerConfig;
import kafka.consumer.ConsumerIterator;
import kafka.consumer.KafkaStream;
import kafka.consumer.Whitelist;
import kafka.javaapi.consumer.ConsumerConnector;
import kafka.serializer.StringDecoder;
import kafka.utils.VerifiableProperties;
import lombok.extern.log4j.Log4j;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Log4j
public class Kafka extends BaseInput {

    private ConsumerConnector consumer;
    private ExecutorService executor;
    private String encoding;
    private Map<String, Integer> topics;
    private Map<String, Integer> topicPatterns;


    public Kafka(Map<String, Object> config, ArrayList<Map> filter,
                 ArrayList<Map> outputs) throws Exception {
        super(config, filter, outputs);
    }

    protected void prepare() {
        //if null, utf-8 encoding will be used
        this.encoding = (String) this.config.get("encoding");
        topics = (Map<String, Integer>) this.config.get("topic");
        topicPatterns = (Map<String,Integer>) this.config.get("topic_pattern");

        Properties props = new Properties();
        HashMap<String, String> consumerSettings = (HashMap<String, String>) this.config.get("consumer_settings");
        consumerSettings.entrySet().stream().forEach(entry -> props.put(entry.getKey(), entry.getValue()));
        consumer = kafka.consumer.Consumer.createJavaConsumerConnector(new ConsumerConfig(props));
        createProcessors();
    }

    public void shutdown() {

        if (consumer != null) consumer.shutdown();
        if (executor != null) executor.shutdown();
        try {
            HashMap<String, String> consumerSettings = (HashMap<String, String>) this.config
                    .get("consumer_settings");

            int timeout = 5000;
            if (consumerSettings.containsKey("auto.commit.interval.ms")) {
                timeout = Integer.parseInt(consumerSettings.get("auto.commit.interval.ms"));
            }

            if (!executor.awaitTermination(timeout, TimeUnit.MILLISECONDS)) {
                log.error("Timed out waiting for consumer threads to shut down, exiting uncleanly");
            }
        } catch (InterruptedException e) {
            log.error("Interrupted during shutdown, exiting uncleanly", e);
        }
    }

    public void emit() {
        VerifiableProperties vp = new VerifiableProperties();
        vp.props().setProperty("serializer.encoding", this.encoding);
        StringDecoder decoder = new StringDecoder(vp);


        if(topicPatterns!=null){
            topicPatterns.entrySet().stream().forEach(entry->{
                String topicPattern = entry.getKey();
                Integer threadCounts = entry.getValue();
                List<KafkaStream<String, String>> consumerStreams =
                        consumer.createMessageStreamsByFilter(
                                new Whitelist(topicPattern), threadCounts,
                                decoder, decoder);
                executor = Executors.newFixedThreadPool(consumerStreams.size());
                consumerStreams.forEach(stream-> executor.submit(new Consumer(stream)));

            });

        }else{
            //Create Consumer Streams Map
            Map<String, List<KafkaStream<String, String>>> consumerMap = consumer.createMessageStreams(topics, decoder, decoder);
            consumerMap.entrySet().forEach(entry->{
                List<KafkaStream<String, String>> streams = entry.getValue();
                executor = Executors.newFixedThreadPool(streams.size());
                streams.forEach(stream -> executor.submit(new Consumer(stream)));
            });
        }
        //Kick off each stream as the number of threads specified
    }

    private class Consumer implements Runnable {
        private KafkaStream<String, String> kafkaStream;

        public Consumer(KafkaStream<String, String> kafkaStream) {
            this.kafkaStream = kafkaStream;
        }

        public void run() {
            ConsumerIterator<String, String> it = kafkaStream.iterator();
            while (it.hasNext()) {
                applyProcessor(it.next().message());
            }
        }
    }
}
