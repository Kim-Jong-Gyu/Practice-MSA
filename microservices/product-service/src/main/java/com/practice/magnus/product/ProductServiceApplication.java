package com.practice.magnus.product;

import com.practice.magnus.product.persistence.ProductEntity;
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
public class ProductServiceApplication {

    private static final Logger LOG = LoggerFactory.getLogger(ProductServiceApplication.class);

    private final ReactiveMongoOperations mongoTemplate;

    public ProductServiceApplication(ReactiveMongoOperations mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(ProductServiceApplication.class, args);

        String mongoDbHost = ctx.getEnvironment().getProperty("spring.mongodb.host");
        String mongoDbPort = ctx.getEnvironment().getProperty("spring.mongodb.port");
        LOG.info("Connected to MongoDb: {}:{}", mongoDbHost, mongoDbPort);
    }

    /**
     * 애플리케이션 시작 시 {@link ProductEntity}에 선언된 인덱스(@Indexed, @CompoundIndex)를
     * MongoDB에 생성한다.
     * <p>
     * Spring Data MongoDB 3.0부터 인덱스 자동 생성(auto-index-creation)이 기본으로 꺼져 있으므로
     * 명시적으로 생성한다. 이미 같은 인덱스가 있으면 MongoDB가 무시하므로 재시작해도 안전하다.
     */
    @EventListener(ContextRefreshedEvent.class)
    public void initIndicesAfterStartup() {
        MappingContext<? extends MongoPersistentEntity<?>, MongoPersistentProperty> mappingContext =
                mongoTemplate.getConverter().getMappingContext();
        IndexResolver resolver = new MongoPersistentEntityIndexResolver(mappingContext);

        ReactiveIndexOperations indexOps = mongoTemplate.indexOps(ProductEntity.class);
        resolver.resolveIndexFor(ProductEntity.class)
                .forEach(index -> indexOps.createIndex(index).block());
    }

}