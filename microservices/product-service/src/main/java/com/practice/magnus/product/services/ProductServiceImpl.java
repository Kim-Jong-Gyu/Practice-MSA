package com.practice.magnus.product.services;

import com.practice.magnus.api.core.product.Product;
import com.practice.magnus.api.core.product.ProductService;
import com.practice.magnus.product.persistence.ProductEntity;
import com.practice.magnus.product.persistence.ProductRepository;
import com.practice.magnus.util.exceptions.InvalidInputException;
import com.practice.magnus.util.exceptions.NotFoundException;
import com.practice.magnus.util.http.ServiceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Random;

import static reactor.core.publisher.Mono.error;

@RestController
public class ProductServiceImpl implements ProductService {

    private static final Logger LOG = LoggerFactory.getLogger(ProductServiceImpl.class);

    private final ServiceUtil serviceUtil;

    private final ProductRepository repository;

    private final ProductMapper mapper;

    @Autowired
    public ProductServiceImpl(ProductRepository repository, ProductMapper mapper, ServiceUtil serviceUtil) {
        this.repository = repository;
        this.mapper = mapper;
        this.serviceUtil = serviceUtil;
    }

    @Override
    public Product createProduct(Product body) {

        if (body.getProductId() < 1) throw new InvalidInputException("Invalid productId: " + body.getProductId());

        ProductEntity entity = mapper.apiToEntity(body);
        Mono<Product> newEntity = repository.save(entity)
            .log()
            .onErrorMap(
                DuplicateKeyException.class,
                ex -> new InvalidInputException("Duplicate key, Product Id: " + body.getProductId()))
            .map(mapper::entityToApi);

        return newEntity.block();
    }

    @Override
    public Mono<Product> getProduct(int productId, int delay, int faultPercent) {

        if (productId < 1) throw new InvalidInputException("Invalid productId: " + productId);

        if (delay > 0) simulateDelay(delay);

        if (faultPercent > 0) throwErrorIfBadLuck(faultPercent);

        return repository.findByProductId(productId)
            .switchIfEmpty(error(new NotFoundException("No product found for productId: " + productId)))
            .log()
            .map(mapper::entityToApi)
            .map(e -> {e.setServiceAddress(serviceUtil.getServiceAddress()); return e;});
    }

    private void simulateDelay(int delay) {
        LOG.debug("Sleeping for {} seconds...", delay);
        try {
            Thread.sleep(delay * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOG.debug("Moving on...");
    }

    private final Random randomNumberGenerator = new Random();

    private void throwErrorIfBadLuck(int faultPercent) {
        int randomThreshold = randomNumberGenerator.nextInt(100) + 1;
        if (faultPercent < randomThreshold) {
            LOG.debug("We got lucky, no error occurred, {} < {}", faultPercent, randomThreshold);
        } else {
            LOG.debug("Bad luck, an error occurred, {} >= {}", faultPercent, randomThreshold);
            throw new RuntimeException("Something went wrong...");
        }
    }

    @Override
    public void deleteProduct(int productId) {

        if (productId < 1) throw new InvalidInputException("Invalid productId: " + productId);

        LOG.debug("deleteProduct: tries to delete an entity with productId: {}", productId);
        repository.findByProductId(productId).log().map(repository::delete).flatMap(e -> e).block();
    }
}