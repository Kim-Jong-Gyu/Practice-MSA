package com.practice.magnus.recommendation;

import com.practice.magnus.recommendation.persistence.RecommendationEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mapping.context.MappingContext;
import org.springframework.data.mongodb.core.ReactiveMongoOperations;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.index.ReactiveIndexOperations;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.mapping.MongoPersistentProperty;

@SpringBootApplication(scanBasePackages = "com.practice.magnus")
public class RecommendationServiceApplication {

    private static final Logger LOG = LoggerFactory.getLogger(RecommendationServiceApplication.class);

    private final ReactiveMongoOperations mongoTemplate;

    public RecommendationServiceApplication(ReactiveMongoOperations mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }


    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(RecommendationServiceApplication.class, args);

        String mongoDbHost = ctx.getEnvironment().getProperty("spring.mongodb.host");
        String mongoDbPort = ctx.getEnvironment().getProperty("spring.mongodb.port");
        LOG.info("Connected to MongoDb: {}:{}", mongoDbHost, mongoDbPort);
    }


    @EventListener(ContextRefreshedEvent.class)
    public void initIndicesAfterStartup() {
        MappingContext<? extends MongoPersistentEntity<?>, MongoPersistentProperty> mappingContext =
                mongoTemplate.getConverter().getMappingContext();
        IndexResolver resolver = new MongoPersistentEntityIndexResolver(mappingContext);

        ReactiveIndexOperations indexOps = mongoTemplate.indexOps(RecommendationEntity.class);
        resolver.resolveIndexFor(RecommendationEntity.class)
                .forEach(index -> indexOps.createIndex(index).block());
    }
}
