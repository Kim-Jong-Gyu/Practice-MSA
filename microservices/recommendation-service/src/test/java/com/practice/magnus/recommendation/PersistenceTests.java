package com.practice.magnus.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import com.practice.magnus.recommendation.persistence.RecommendationEntity;
import com.practice.magnus.recommendation.persistence.RecommendationRepository;
import com.practice.magnus.util.test.MongoDbTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;

@DisplayName("Recommendation 영속성 테스트")
@DataMongoTest(properties = {"spring.data.mongodb.auto-index-creation=true", "spring.cloud.config.enabled=false"})
class PersistenceTests extends MongoDbTestBase {

    @Autowired
    private RecommendationRepository repository;

    private RecommendationEntity savedEntity;

    @BeforeEach
    void setupDb() {
        repository.deleteAll().block();

        RecommendationEntity entity = new RecommendationEntity(1, 2, "a", 3, "c");
        savedEntity = repository.save(entity).block();

        assertEqualsRecommendation(entity, savedEntity);
    }

    @Test
    @DisplayName("새 추천을 저장하면 ID로 조회할 수 있고 전체 개수가 늘어난다")
    void saveNewRecommendation_thenFoundById() {
        RecommendationEntity newEntity = new RecommendationEntity(1, 3, "a", 3, "c");
        repository.save(newEntity).block();

        RecommendationEntity foundEntity = repository.findById(newEntity.getId()).block();
        assertEqualsRecommendation(newEntity, foundEntity);

        assertThat(repository.count().block()).isEqualTo(2);
    }

    @Test
    @DisplayName("작성자를 수정하면 변경 내용이 저장되고 버전이 1 증가한다")
    void updateAuthor_thenVersionIncreased() {
        savedEntity.setAuthor("a2");
        repository.save(savedEntity).block();

        RecommendationEntity foundEntity = repository.findById(savedEntity.getId()).block();
        assertThat(foundEntity).isNotNull();
        assertThat(foundEntity.getVersion()).isEqualTo(1);
        assertThat(foundEntity.getAuthor()).isEqualTo("a2");
    }

    @Test
    @DisplayName("추천을 삭제하면 더 이상 존재하지 않는다")
    void deleteRecommendation_thenNotExists() {
        repository.delete(savedEntity).block();
        assertThat(repository.existsById(savedEntity.getId()).block()).isFalse();
    }

    @Test
    @DisplayName("productId로 추천 목록을 조회할 수 있다")
    void findByProductId_thenReturnsRecommendations() {
        List<RecommendationEntity> entityList =
                repository.findByProductId(savedEntity.getProductId()).collectList().block();

        assertThat(entityList).hasSize(1);
        assertEqualsRecommendation(savedEntity, entityList.get(0));
    }

    @Test
    @DisplayName("같은 productId와 recommendationId로 저장하면 DuplicateKeyException이 발생한다")
    void saveDuplicateKey_thenThrowsDuplicateKey() {
        RecommendationEntity entity = new RecommendationEntity(1, 2, "a", 3, "c");

        assertThatThrownBy(() -> repository.save(entity).block())
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("오래된 버전의 엔티티로 수정하면 낙관적 락 예외가 발생하고 먼저 수정한 내용만 남는다")
    void updateWithStaleVersion_thenThrowsOptimisticLock() {
        // 같은 엔티티를 두 개의 객체로 조회
        RecommendationEntity entity1 = repository.findById(savedEntity.getId()).block();
        RecommendationEntity entity2 = repository.findById(savedEntity.getId()).block();
        assertThat(entity1).isNotNull();
        assertThat(entity2).isNotNull();

        // 첫 번째 객체로 수정
        entity1.setAuthor("a1");
        repository.save(entity1).block();

        // 두 번째 객체는 이전 버전을 들고 있으므로 낙관적 락 에러가 나야 함
        entity2.setAuthor("a2");
        assertThatThrownBy(() -> repository.save(entity2).block())
                .isInstanceOf(OptimisticLockingFailureException.class);

        // DB에는 첫 번째 수정만 반영돼 있어야 함
        RecommendationEntity updatedEntity = repository.findById(savedEntity.getId()).block();
        assertThat(updatedEntity).isNotNull();
        assertThat(updatedEntity.getVersion()).isEqualTo(1);
        assertThat(updatedEntity.getAuthor()).isEqualTo("a1");
    }

    private void assertEqualsRecommendation(RecommendationEntity expected, RecommendationEntity actual) {
        assertThat(actual).isNotNull();
        assertThat(actual.getId()).isEqualTo(expected.getId());
        assertThat(actual.getVersion()).isEqualTo(expected.getVersion());
        assertThat(actual.getProductId()).isEqualTo(expected.getProductId());
        assertThat(actual.getRecommendationId()).isEqualTo(expected.getRecommendationId());
        assertThat(actual.getAuthor()).isEqualTo(expected.getAuthor());
        assertThat(actual.getRating()).isEqualTo(expected.getRating());
        assertThat(actual.getContent()).isEqualTo(expected.getContent());
    }
}