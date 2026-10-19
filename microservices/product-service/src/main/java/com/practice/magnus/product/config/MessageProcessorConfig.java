package com.practice.magnus.product.config;

import com.practice.magnus.api.core.product.Product;
import com.practice.magnus.api.core.product.ProductService;
import com.practice.magnus.api.event.Event;
import com.practice.magnus.util.exceptions.EventProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

@Configuration
public class MessageProcessorConfig {

    private static final Logger LOG = LoggerFactory.getLogger(MessageProcessorConfig.class);

    private final ProductService productService;

    public MessageProcessorConfig(ProductService productService) {
        this.productService = productService;
    }

    @Bean
    public Consumer<Event<Integer, Product>> messageProcessor() {
        return event -> {
            LOG.info("Process message created at {}...", event.getEventCreatedAt());

            switch (event.getEventType()) {

                case CREATE:
                    Product product = event.getData();
                    LOG.info("Create product with ID: {}", product.getProductId());
                    productService.createProduct(product);
                    break;

                case DELETE:
                    int productId = event.getKey();
                    LOG.info("Delete product with ProductID: {}", productId);
                    productService.deleteProduct(productId);
                    break;

                default:
                    String errorMessage = "Incorrect event type: " + event.getEventType() + ", expected a CREATE or DELETE event";
                    LOG.warn(errorMessage);
                    throw new EventProcessingException(errorMessage);
            }

            LOG.info("Message processing done!");
        };
    }
}