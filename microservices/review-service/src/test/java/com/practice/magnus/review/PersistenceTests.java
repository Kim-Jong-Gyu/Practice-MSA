package com.practice.magnus.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED;

import java.util.List;
import com.practice.magnus.review.persistence.ReviewEntity;
import com.practice.magnus.review.persistence.ReviewRepository;
import com.practice.magnus.util.test.MySqlTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("Review 영속성 테스트")
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.cloud.config.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = NOT_SUPPORTED)
class PersistenceTests extends MySqlTestBase {

    @Autowired
    private ReviewRepository repository;

    private ReviewEntity savedEntity;

    @BeforeEach
    void setupDb() {
        repository.deleteAll();

        ReviewEntity entity = new ReviewEntity(1, 2, "a", "s", "c");
        savedEntity = repository.save(entity);

        assertEqualsReview(entity, savedEntity);
    }

    @Test
    @DisplayName("새 리뷰를 저장하면 ID로 조회할 수 있고 전체 개수가 늘어난다")
    void saveNewReview_thenFoundById() {
        ReviewEntity newEntity = new ReviewEntity(1, 3, "a", "s", "c");
        repository.save(newEntity);

        ReviewEntity foundEntity = repository.findById(newEntity.getId()).orElseThrow();
        assertEqualsReview(newEntity, foundEntity);

        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("작성자를 수정하면 변경 내용이 저장되고 버전이 1 증가한다")
    void updateAuthor_thenVersionIncreased() {
        savedEntity.setAuthor("a2");
        repository.save(savedEntity);

        ReviewEntity foundEntity = repository.findById(savedEntity.getId()).orElseThrow();
        assertThat(foundEntity.getVersion()).isEqualTo(1);
        assertThat(foundEntity.getAuthor()).isEqualTo("a2");
    }

    @Test
    @DisplayName("리뷰를 삭제하면 더 이상 존재하지 않는다")
    void deleteReview_thenNotExists() {
        repository.delete(savedEntity);
        assertThat(repository.existsById(savedEntity.getId())).isFalse();
    }

    @Test
    @DisplayName("productId로 리뷰 목록을 조회할 수 있다")
    void findByProductId_thenReturnsReviews() {
        List<ReviewEntity> entityList = repository.findByProductId(savedEntity.getProductId());

        assertThat(entityList).hasSize(1);
        assertEqualsReview(savedEntity, entityList.get(0));
    }

    @Test
    @DisplayName("같은 productId와 reviewId로 저장하면 DataIntegrityViolationException이 발생한다")
    void saveDuplicateKey_thenThrowsDataIntegrityViolation() {
        ReviewEntity entity = new ReviewEntity(1, 2, "a", "s", "c");

        assertThatThrownBy(() -> repository.save(entity))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("오래된 버전의 엔티티로 수정하면 낙관적 락 예외가 발생하고 먼저 수정한 내용만 남는다")
    void updateWithStaleVersion_thenThrowsOptimisticLock() {
        // 같은 엔티티를 두 개의 객체로 조회
        ReviewEntity entity1 = repository.findById(savedEntity.getId()).orElseThrow();
        ReviewEntity entity2 = repository.findById(savedEntity.getId()).orElseThrow();

        // 첫 번째 객체로 수정
        entity1.setAuthor("a1");
        repository.save(entity1);

        // 두 번째 객체는 이전 버전을 들고 있으므로 낙관적 락 에러가 나야 함
        entity2.setAuthor("a2");
        assertThatThrownBy(() -> repository.save(entity2))
                .isInstanceOf(OptimisticLockingFailureException.class);

        // DB에는 첫 번째 수정만 반영돼 있어야 함
        ReviewEntity updatedEntity = repository.findById(savedEntity.getId()).orElseThrow();
        assertThat(updatedEntity.getVersion()).isEqualTo(1);
        assertThat(updatedEntity.getAuthor()).isEqualTo("a1");
    }

    private void assertEqualsReview(ReviewEntity expected, ReviewEntity actual) {
        assertThat(actual.getId()).isEqualTo(expected.getId());
        assertThat(actual.getVersion()).isEqualTo(expected.getVersion());
        assertThat(actual.getProductId()).isEqualTo(expected.getProductId());
        assertThat(actual.getReviewId()).isEqualTo(expected.getReviewId());
        assertThat(actual.getAuthor()).isEqualTo(expected.getAuthor());
        assertThat(actual.getSubject()).isEqualTo(expected.getSubject());
        assertThat(actual.getContent()).isEqualTo(expected.getContent());
    }
}