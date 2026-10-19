package com.practice.magnus.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.practice.magnus.product.persistence.ProductEntity;
import com.practice.magnus.product.persistence.ProductRepository;
import com.practice.magnus.util.test.MongoDbTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.test.StepVerifier;

@DisplayName("Product 영속성 테스트")
@DataMongoTest(properties = "spring.data.mongodb.auto-index-creation=true")
class PersistenceTests extends MongoDbTestBase {

    @Autowired
    private ProductRepository repository;

    private ProductEntity savedEntity;

    @BeforeEach
    void setupDb() {
        StepVerifier.create(repository.deleteAll()).verifyComplete();

        ProductEntity entity = new ProductEntity(1, "n", 1);
        StepVerifier.create(repository.save(entity))
                .expectNextMatches(createdEntity -> {
                    savedEntity = createdEntity;
                    return areProductEqual(entity, savedEntity);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("새 상품을 저장하면 ID로 조회할 수 있고 전체 개수가 늘어난다")
    void saveNewProduct_thenFoundById() {
        ProductEntity newEntity = new ProductEntity(2, "n", 2);

        StepVerifier.create(repository.save(newEntity))
                .expectNextMatches(createdEntity -> newEntity.getProductId() == createdEntity.getProductId())
                .verifyComplete();

        StepVerifier.create(repository.findById(newEntity.getId()))
                .expectNextMatches(foundEntity -> areProductEqual(newEntity, foundEntity))
                .verifyComplete();

        StepVerifier.create(repository.count()).expectNext(2L).verifyComplete();
    }

    @Test
    @DisplayName("상품 이름을 수정하면 변경 내용이 저장되고 버전이 1 증가한다")
    void updateProductName_thenVersionIncreased() {
        savedEntity.setName("n2");
        StepVerifier.create(repository.save(savedEntity))
                .expectNextMatches(updatedEntity -> updatedEntity.getName().equals("n2"))
                .verifyComplete();

        StepVerifier.create(repository.findById(savedEntity.getId()))
                .expectNextMatches(foundEntity ->
                        foundEntity.getVersion() == 1 &&
                                foundEntity.getName().equals("n2"))
                .verifyComplete();
    }

    @Test
    @DisplayName("상품을 삭제하면 더 이상 존재하지 않는다")
    void deleteProduct_thenNotExists() {
        StepVerifier.create(repository.delete(savedEntity)).verifyComplete();
        StepVerifier.create(repository.existsById(savedEntity.getId())).expectNext(false).verifyComplete();
    }

    @Test
    @DisplayName("productId로 상품을 조회할 수 있다")
    void findByProductId_thenReturnsProduct() {
        StepVerifier.create(repository.findByProductId(savedEntity.getProductId()))
                .expectNextMatches(foundEntity -> areProductEqual(savedEntity, foundEntity))
                .verifyComplete();
    }

    @Test
    @DisplayName("이미 존재하는 productId로 저장하면 DuplicateKeyException이 발생한다")
    void saveDuplicateProductId_thenThrowsDuplicateKey() {
        ProductEntity entity = new ProductEntity(savedEntity.getProductId(), "n", 1);
        StepVerifier.create(repository.save(entity))
                .expectError(DuplicateKeyException.class)
                .verify();
    }

    @Test
    @DisplayName("오래된 버전의 엔티티로 수정하면 낙관적 락 예외가 발생하고 먼저 수정한 내용만 남는다")
    void updateWithStaleVersion_thenThrowsOptimisticLock() {
        // 같은 엔티티를 두 개의 객체로 조회
        ProductEntity entity1 = repository.findById(savedEntity.getId()).block();
        ProductEntity entity2 = repository.findById(savedEntity.getId()).block();
        assertThat(entity1).isNotNull();
        assertThat(entity2).isNotNull();

        // 첫 번째 객체로 수정
        entity1.setName("n1");
        repository.save(entity1).block();

        // 두 번째 객체는 이전 버전을 들고 있으므로 낙관적 락 에러가 나야 함
        StepVerifier.create(repository.save(entity2))
                .expectError(OptimisticLockingFailureException.class)
                .verify();

        // DB에는 첫 번째 수정만 반영돼 있어야 함
        StepVerifier.create(repository.findById(savedEntity.getId()))
                .expectNextMatches(foundEntity ->
                        foundEntity.getVersion() == 1 &&
                                foundEntity.getName().equals("n1"))
                .verifyComplete();
    }

    private boolean areProductEqual(ProductEntity expected, ProductEntity actual) {
        return expected.getId().equals(actual.getId())
                && expected.getVersion().equals(actual.getVersion())
                && expected.getProductId() == actual.getProductId()
                && expected.getName().equals(actual.getName())
                && expected.getWeight() == actual.getWeight();
    }
}